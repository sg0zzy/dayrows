#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build, verify and archive the Android debug target without changing VERSION."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

from artifacts import Storage, limit_value

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {'testDebugUnitTest', 'lintDebug', '--offline', '--no-daemon', '--console=plain',
           '--rerun-tasks', '--no-build-cache', '--refresh-dependencies', '--stacktrace',
           '--warning-mode=all'}


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout.strip()


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_digest(root):
    names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others',
                                     '--exclude-standard'], cwd=root).split(b'\0')
    # Ignored inputs in source trees still affect a build and must be identified.
    paths = {os.fsdecode(name) for name in names if name}
    for folder in ('app/src', 'icons', 'gradle', 'scripts'):
        paths.update(str(path.relative_to(root)) for path in (root / folder).rglob('*')
                     if path.is_file() and '__pycache__' not in path.parts
                     and path.suffix not in ('.jks', '.keystore'))
    digest = hashlib.sha256()
    for name in sorted(paths):
        path = root / name
        if path.is_symlink():
            raise ValueError('Source symlinks are unsupported: ' + name)
        if path.is_file():
            data = path.read_bytes()
            encoded = os.fsencode(name)
            digest.update(len(encoded).to_bytes(8, 'big') + encoded)
            digest.update(len(data).to_bytes(8, 'big') + data)
    return digest.hexdigest()


def version_code(version):
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('VERSION must be a canonical X.Y.Z core')
    major, minor, patch = map(int, version.split('.'))
    code = major * 1_000_000 + minor * 1_000 + patch - 999
    if major > 2100 or minor > 999 or patch > 999 or not 1 <= code <= 2_100_000_000:
        raise ValueError('Version is outside the existing Android debug code allocation')
    return code


def sdk_path():
    # Android's project-local sdk.dir takes precedence over environment variables.
    properties = ROOT / 'local.properties'
    if properties.exists():
        text = properties.read_text()
        entries = [line.strip() for line in text.splitlines()
                   if line.strip() and not line.lstrip().startswith(('#', '!'))]
        if len(entries) != 1 or not entries[0].startswith('sdk.dir'):
            raise ValueError('Managed local.properties may contain only sdk.dir and comments')
        sdk = re.search(r'^sdk\.dir\s*=\s*(.+)$', text, re.M)
        if not sdk:
            raise ValueError('local.properties must contain sdk.dir for managed builds')
        return Path(sdk[1].strip().replace('\\:', ':').replace('\\\\', '\\'))
    value = os.getenv('ANDROID_HOME') or os.getenv('ANDROID_SDK_ROOT')
    if not value:
        raise ValueError('Set ANDROID_HOME or configure sdk.dir in local.properties')
    return Path(value)


def verify_apk(apk, sdk, version):
    tools = sdk / 'build-tools' / '36.0.0'
    signature = run(str(tools / 'apksigner'), 'verify', '--print-certs', str(apk))
    certificate = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)', signature)
    if not certificate:
        raise ValueError('Debug APK lacks a verified signing certificate')
    badging = run(str(tools / 'aapt'), 'dump', 'badging', str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    # The debug build installs beside production: package gains the .debug suffix and
    # versionName the -debug suffix; the authoritative core (VERSION) still binds them.
    if not package or package.groups() != ('it.stefanogozzi.dayrows.debug', str(version_code(version)),
                                           f'{version}-debug'):
        raise ValueError('Debug APK package/version differs from the authoritative metadata '
                         '(expected it.stefanogozzi.dayrows.debug / core version with -debug suffix)')
    if "sdkVersion:'26'" not in badging or "targetSdkVersion:'36'" not in badging:
        raise ValueError('Unexpected Android SDK compatibility')
    return dict(application_id=package[1], version_code=int(package[2]), version_name=package[3],
                signer_sha256=certificate[1].lower(), signing_profile='test-only-debug')


@contextmanager
def build_lock(storage):
    # One compilation at a time in this working copy; Gradle's replaceable output
    # path cannot otherwise safely be attributed to a managed invocation.
    import fcntl
    with storage.locked(create=True):
        fd = os.open('.compilation.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW,
                     0o600, dir_fd=storage.fd)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        yield
    finally:
        os.close(fd)


def build(arguments):
    if sys.version_info < (3, 11) or platform.system() not in ('Linux', 'Darwin'):
        raise ValueError('Managed builds require Python 3.11+ on Linux or macOS')
    if any(arg not in ALLOWED for arg in arguments):
        raise ValueError('Unsupported managed Gradle argument; see docs/DEVELOPMENT.md')
    os.chdir(ROOT)
    injected = ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'GRADLE_OPTS')
    if any(os.getenv(key) for key in injected) or any(key.startswith('ORG_GRADLE_PROJECT_') for key in os.environ):
        raise ValueError('Managed builds do not accept environment-injected Java/Gradle options')
    gradle_home = Path(os.getenv('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    customizations = [gradle_home / name for name in ('gradle.properties', 'init.gradle', 'init.gradle.kts')]
    if any(path.exists() for path in customizations) or any((gradle_home / 'init.d').glob('*')):
        raise ValueError('Managed builds require no global Gradle properties/init scripts')
    for tool in ('java', 'git'):
        if shutil.which(tool) is None:
            raise ValueError('Missing required tool: ' + tool)
    retention = limit_value(int(os.getenv('DAYROWS_ARTIFACT_LIMIT', '10')))
    sdk = sdk_path()
    for tool in ('apksigner', 'aapt'):
        if not (sdk / 'build-tools/36.0.0' / tool).is_file():
            raise ValueError('Android build-tools 36.0.0 required')
    sdk_inputs = ['build-tools/36.0.0/aapt', 'build-tools/36.0.0/apksigner',
                  'build-tools/36.0.0/source.properties', 'platforms/android-36/android.jar',
                  'platforms/android-36/source.properties']
    sdk_identity = {name: sha256(sdk / name) for name in sdk_inputs}
    storage = Storage(ROOT / 'local_artifacts')
    with build_lock(storage):
        version = (ROOT / 'VERSION').read_text().strip()
        version_code(version)
        commit = run('git', 'rev-parse', '--verify', 'HEAD')
        if run('git', 'ls-files', '--stage').find('160000 ') >= 0:
            raise ValueError('Submodules need an explicit source identity mapping before building')
        before = source_digest(ROOT)
        # Also observe machine-local effective configuration without disclosing it.
        properties = ROOT / 'local.properties'
        local_properties = sha256(properties) if properties.exists() else None
        config_paths = ['DEVELOPMENT_STANDARDS.md', 'VERSION', 'scripts/build_debug.py',
                        'scripts/artifacts.py', 'app/build.gradle.kts', 'build.gradle.kts',
                        'settings.gradle.kts', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties',
                        'docs/DEVELOPMENT.md', 'docs/standards-lock.json', '.github/workflows/build-apk.yml']
        config_digest = hashlib.sha256(b''.join(path.encode() + bytes.fromhex(sha256(ROOT / path))
                                              for path in config_paths)).hexdigest()
        metadata = dict(product='DayRows', product_version=version, development_core=version,
                        channel='ci-debug' if os.getenv('GITHUB_ACTIONS') == 'true' else 'local-debug',
                        official_release=False, commit_sha=commit,
                        dirty=bool(run('git', 'status', '--porcelain')),
                        source=dict(algorithm='sha256', digest=before,
                                    scope='Git non-ignored files plus ignored app/src, icons, gradle and scripts inputs; '
                                          'excludes outputs/caches, __pycache__, local.properties and signing keys; '
                                          'SDK/toolchain identified separately. No submodules or source symlinks.'),
                        standards_commit=json.loads((ROOT / 'docs/standards-lock.json').read_text())['commit'],
                        configuration_sha256=config_digest,
                        environment=dict(os=platform.system(), architecture=platform.machine(),
                                         python=platform.python_version(),
                                         java=subprocess.run(['java', '-version'], check=True, capture_output=True,
                                                             text=True).stderr.strip(),
                                         gradle='8.14.3', agp='8.13.0', kotlin='2.2.20',
                                         android_compile_sdk=36, android_build_tools='36.0.0',
                                         sdk_inputs_sha256=sdk_identity,
                                         dependency_locks='none; fixed declared versions, transitive locking not yet adopted'),
                        options=['assembleDebug', *arguments], retention_limit=retention,
                        targets=[dict(id='android-debug', required=True, platform='android',
                                      architecture='universal-jvm', kind='apk', outcome='in_progress')])
        if os.getenv('GITHUB_ACTIONS') == 'true':
            metadata['ci'] = dict(repository=os.getenv('GITHUB_REPOSITORY'),
                                  run_id=os.getenv('GITHUB_RUN_ID'), attempt=os.getenv('GITHUB_RUN_ATTEMPT'),
                                  event=os.getenv('GITHUB_EVENT_NAME'), ref=os.getenv('GITHUB_REF'),
                                  event_sha=os.getenv('GITHUB_SHA'), revision_policy='event SHA; PR proposed merge',
                                  pr_head_sha=os.getenv('DAYROWS_PR_HEAD_SHA'),
                                  pr_base_sha=os.getenv('DAYROWS_PR_BASE_SHA'))
        directory, metadata = storage.reserve(metadata)
        stage = 'compile-and-checks'
        try:
            subprocess.run(['./gradlew', 'assembleDebug', *arguments], check=True)
            stage = 'source-coherence'
            if source_digest(ROOT) != before or (sha256(properties) if properties.exists() else None) != local_properties:
                raise ValueError('Inputs changed during compilation; retry for coherent source identity')
            if {name: sha256(sdk / name) for name in sdk_inputs} != sdk_identity:
                raise ValueError('SDK inputs changed during compilation')
            apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
            stage = 'package-verification'
            package = verify_apk(apk, sdk, version)
            outputs = []
            stage = 'archive'
            name = f'DayRows-{version}-android-universal-debug.apk'
            for source, relative in [(apk, name),
                                     (ROOT / 'icons/dayrows-play-icon-512.png', 'store-artwork/dayrows-play-icon-512.png'),
                                     (ROOT / 'icons/dayrows-feature-1024x500.png', 'store-artwork/dayrows-feature-1024x500.png')]:
                target = directory / relative
                target.parent.mkdir(exist_ok=True)
                with source.open('rb') as src, target.open('xb') as dst:
                    shutil.copyfileobj(src, dst)
                outputs.append(dict(path=relative, bytes=target.stat().st_size, sha256=sha256(target)))
            # Verify the archived final bytes, not only Gradle's mutable output.
            if verify_apk(directory / name, sdk, version) != package:
                raise ValueError('Archived package verification mismatch')
            if source_digest(ROOT) != before:
                raise ValueError('Inputs changed during archive/verification; retry for coherent identity')
            metadata['targets'][0].update(outcome='succeeded', package=package, artifacts=outputs)
            stage = 'completion-metadata'
            storage.finish(directory, metadata, success=True)
        except BaseException as error:
            metadata['targets'][0].update(outcome='failed', stage=stage, reason=type(error).__name__)
            storage.finish(directory, metadata, success=False)
            print(f'Build failed; retained state: {directory / "build-info.json"}', file=sys.stderr)
            raise
        print(f'Verified debug build: {directory}')
        # Failure here is maintenance failure after compilation success, not a failed build.
        try:
            print(json.dumps(dict(cleanup=storage.clean(retention, apply=True)), indent=2))
        except Exception:
            print('Compilation succeeded; artifact maintenance failed. Inspect retained outputs.', file=sys.stderr)
            raise


if __name__ == '__main__':
    build(sys.argv[1:])
