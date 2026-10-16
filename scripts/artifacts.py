#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Managed local artifacts; POSIX file locks and fd-relative deletion (Python 3.11+)."""
import argparse
from collections import Counter
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import stat
import uuid

MANAGER = 'dayrows-local-v1'
SCHEMA = 1
ROOT = Path(__file__).resolve().parents[1] / 'local_artifacts'


def now():
    return datetime.now(timezone.utc).isoformat()


def identity(info):
    return info.st_dev, info.st_ino


def atomic_json(directory, name, value):
    """Only called with the storage lock; temporary file never follows a symlink."""
    temporary = directory / ('.pending-' + uuid.uuid4().hex)
    try:
        with temporary.open('x') as stream:
            json.dump(value, stream, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, directory / name)
        fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    finally:
        temporary.unlink(missing_ok=True)


def limit_value(value):
    if type(value) is not int or value < 0:
        raise ValueError('Retention must be a nonnegative integer')
    return value


class Storage:
    def __init__(self, root=ROOT):
        self.root = Path(root).absolute()

    @contextmanager
    def locked(self, create=False):
        if create:
            self.root.mkdir(exist_ok=True)
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        lock = None
        try:
            root_info = os.fstat(fd)
            if root_info.st_uid != os.getuid():
                raise ValueError('Artifact root must be owned by the current user')
            flags = os.O_RDWR | os.O_NOFOLLOW | (os.O_CREAT if create else 0)
            lock = os.open('.lock', flags, 0o600, dir_fd=fd)
            info = os.fstat(lock)
            if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != os.getuid():
                raise ValueError('Unsafe artifact lock')
            fcntl.flock(lock, fcntl.LOCK_EX)
            if identity(os.stat(self.root, follow_symlinks=False)) != identity(root_info):
                raise ValueError('Artifact root changed')
            self.fd = fd
            yield
        finally:
            if lock is not None:
                os.close(lock)
            os.close(fd)

    def reserve(self, metadata):
        with self.locked(create=True):
            build_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ-') + uuid.uuid4().hex
            directory = self.root / build_id
            directory.mkdir()  # exclusive; direct child
            record = dict(metadata, manager=MANAGER, schema=SCHEMA, build_id=build_id,
                          state='in_progress', started_utc=now(), completed_utc=None,
                          completion_sequence=None)
            atomic_json(directory, 'build-info.json', record)
        return directory, record

    def read(self, name):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', name):
            raise ValueError('Expected a managed direct child build ID')
        fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=self.fd)
        try:
            info = os.fstat(fd)
            if info.st_uid != os.getuid():
                raise ValueError('Foreign directory owner')
            mf = os.open('build-info.json', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
            with os.fdopen(mf) as stream:
                if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                    raise ValueError('Manifest is not regular')
                record = json.load(stream)
            if (record.get('manager') != MANAGER or record.get('schema') != SCHEMA
                    or type(record.get('schema')) is not int
                    or record.get('build_id') != name
                    or record.get('state') not in ('in_progress', 'succeeded', 'failed')):
                raise ValueError('Unknown or malformed manager/state/identity')
            datetime.fromisoformat(record['started_utc'])
            if record['state'] == 'succeeded':
                seq = record.get('completion_sequence')
                if type(seq) is not int or seq < 1 or not record.get('completed_utc'):
                    raise ValueError('Invalid completion metadata')
                datetime.fromisoformat(record['completed_utc'])
            try:
                os.stat('.keep', dir_fd=fd, follow_symlinks=False)
                protected = True  # any entry, including a dangling symlink
            except FileNotFoundError:
                protected = False
            return record, protected, identity(info)
        finally:
            os.close(fd)

    def finish(self, directory, record, success):
        with self.locked():
            current, _, _ = self.read(directory.name)
            if current['state'] != 'in_progress':
                raise ValueError('Only an in-progress build can complete')
            if success:
                sequence_path = self.root / '.sequence.json'
                if sequence_path.is_symlink():
                    raise ValueError('Unsafe completion sequence')
                if sequence_path.exists():
                    sequence_fd = os.open('.sequence.json', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                                          dir_fd=self.fd)
                    with os.fdopen(sequence_fd) as stream:
                        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                            raise ValueError('Completion sequence is not a regular file')
                        previous = json.load(stream)
                    if type(previous) is not int or previous < 0:
                        raise ValueError('Invalid durable completion sequence')
                else:
                    previous = 0
                for child in self.root.iterdir():
                    if child.name.startswith('.'):
                        continue
                    try:
                        other, _, _ = self.read(child.name)
                    except (OSError, ValueError, TypeError, AttributeError, KeyError):
                        continue
                    if other['state'] == 'succeeded' and other['completion_sequence'] > previous:
                        raise ValueError('Missing/regressed completion sequence; inspect storage')
                record['completion_sequence'] = previous + 1
                atomic_json(self.root, '.sequence.json', previous + 1)
            record.update(state='succeeded' if success else 'failed', completed_utc=now())
            atomic_json(directory, 'build-info.json', record)

    def plan(self, limit):
        limit_value(limit)
        rows = []
        for child in sorted(self.root.iterdir()):
            if child.name.startswith('.'):
                continue
            try:
                record, protected, inode = self.read(child.name)
                reason = 'protected' if protected else record['state']
                rows.append(dict(build_id=child.name, reason=reason, protected=protected,
                                 sequence=record.get('completion_sequence'), inode=inode))
            except (OSError, ValueError, TypeError, AttributeError, KeyError) as error:
                rows.append(dict(build_id=child.name, reason='unsafe/unknown: ' + type(error).__name__))
        counts = Counter(row['sequence'] for row in rows if row.get('sequence') is not None)
        eligible = []
        for row in rows:
            if row['reason'] == 'succeeded':
                if counts[row['sequence']] != 1:
                    row['reason'] = 'ambiguous completion sequence'
                else:
                    eligible.append(row)
        eligible.sort(key=lambda row: row['sequence'], reverse=True)
        for index, row in enumerate(eligible):
            row['reason'] = 'retain' if index < limit else 'delete'
        return rows

    def clean(self, limit=10, apply=False):
        limit_value(limit)
        if not self.root.exists() and not self.root.is_symlink():
            return []  # dry-run does not create a root/lock
        with self.locked():
            rows = self.plan(limit)
            for row in rows:
                if apply and row['reason'] == 'delete':
                    self.delete(row['build_id'], row['inode'], {'succeeded'}, allow_protected=False)
                    row['reason'] = 'deleted'
            return rows

    def delete(self, name, expected_inode, states, allow_protected):
        # Called under the storage lock. Revalidate state, ownership, protection,
        # root/child inode and containment immediately before fd-relative rmtree.
        record, protected, inode = self.read(name)
        if (inode != expected_inode or record['state'] not in states
                or (protected and not allow_protected)):
            raise ValueError('Build changed or became protected; deletion cancelled')
        if identity(os.stat(self.root, follow_symlinks=False)) != identity(os.fstat(self.fd)):
            raise ValueError('Artifact root changed; deletion cancelled')
        if not shutil.rmtree.avoids_symlink_attacks:
            raise ValueError('Host lacks symlink-safe deletion')
        if identity(os.stat(name, dir_fd=self.fd, follow_symlinks=False)) != inode:
            raise ValueError('Build directory substituted')
        shutil.rmtree(name, dir_fd=self.fd)

    def protect(self, name):
        with self.locked():
            self.read(name)
            path = self.root / name / '.keep'
            if not os.path.lexists(path):
                with path.open('x'):
                    pass

    def remove(self, name, confirmed=False):
        with self.locked():
            record, protected, inode = self.read(name)
            if not confirmed:
                return dict(build_id=name, state=record['state'], protected=protected,
                            reason='explicit removal requires --confirm-build-id matching the ID')
            self.delete(name, inode, {record['state']}, allow_protected=True)
            return dict(build_id=name, reason='explicitly deleted')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    clean = commands.add_parser('clean')
    clean.add_argument('--limit', type=int, default=10)
    mode = clean.add_mutually_exclusive_group()
    mode.add_argument('--dry-run', action='store_true')
    mode.add_argument('--apply', action='store_true')
    protect = commands.add_parser('protect')
    protect.add_argument('build_id')
    remove = commands.add_parser('remove')
    remove.add_argument('build_id')
    remove.add_argument('--confirm-build-id')
    args = parser.parse_args()
    storage = Storage()
    if args.command == 'clean':
        result = storage.clean(args.limit, args.apply)
    elif args.command == 'protect':
        storage.protect(args.build_id)
        result = {'protected': args.build_id}
    else:
        if args.confirm_build_id and args.confirm_build_id != args.build_id:
            parser.error('Confirmation must match the selected build ID')
        result = storage.remove(args.build_id, args.confirm_build_id == args.build_id)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
