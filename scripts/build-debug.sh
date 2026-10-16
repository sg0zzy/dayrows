#!/usr/bin/env bash
# Build and archive a local debug APK. Extra arguments are passed to Gradle.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
for build_tool in java python3 git; do
    command -v "$build_tool" >/dev/null 2>&1 || { echo "Missing required tool: $build_tool" >&2; exit 1; }
done
if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" && ! -f local.properties ]]; then
    echo "Set ANDROID_HOME to your Android SDK, or add sdk.dir to local.properties (see README.md)." >&2
    exit 1
fi
python3 - "$@" <<'PY'
import datetime, hashlib, json, pathlib, shutil, subprocess, sys, uuid

root = pathlib.Path.cwd()
version = (root / 'VERSION').read_text().strip()
build_time = datetime.datetime.now(datetime.timezone.utc)
build_id = build_time.strftime('%Y%m%dT%H%M%S.%fZ') + '-' + uuid.uuid4().hex[:12]
archive = root / 'dist' / 'local' / version / build_id
archive.mkdir(parents=True, exist_ok=False)

def git(*args):
    return subprocess.run(['git', *args], capture_output=True, check=False).stdout

def source_digest():
    digest = hashlib.sha256()
    files = git('ls-files', '-z', '--cached', '--others', '--exclude-standard').split(b'\0')
    for filename in sorted(set(files) - {b''}):
        path = root / filename.decode()
        if path.is_file():
            data = path.read_bytes()
            digest.update(len(filename).to_bytes(8, 'big') + filename)
            digest.update(len(data).to_bytes(8, 'big') + data)
    return digest.hexdigest()

commit = git('rev-parse', '--verify', 'HEAD').decode().strip() or None
status = git('status', '--porcelain', '-z')
before = source_digest()
metadata = {
    'product': 'DayRows', 'version': version, 'build_id': build_id,
    'utc_time': build_time.isoformat(), 'commit_sha': commit,
    'dirty': bool(status) or commit is None, 'source_sha256': before,
    'distribution': 'local-debug', 'official_release': False,
    'platform': 'Android', 'architecture': 'universal (Kotlin/JVM)',
    'toolchain': {'gradle': '8.14.3', 'agp': '8.13.0', 'kotlin': '2.2.20',
                  'compile_sdk': 36, 'java': subprocess.run(['java', '-version'], capture_output=True, text=True).stderr.strip()},
}
manifest = archive / 'build-manifest.json'
try:
    subprocess.run(['./gradlew', 'assembleDebug', *sys.argv[1:]], check=True)
    if source_digest() != before:
        raise RuntimeError('Source changed during the build; retry to archive a consistent build.')
    apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
    target = archive / f'DayRows-{version}-debug.apk'
    # Exclusive creation protects archived outputs even in a collision.
    with apk.open('rb') as src, target.open('xb') as dst:
        shutil.copyfileobj(src, dst)
    metadata['outputs'] = [{'filename': target.name, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}]
    # Store listing artwork travels alongside the APK rather than inside the app.
    store_artwork = archive / 'store-artwork'
    store_artwork.mkdir()
    for name in ('dayrows-play-icon-512.png', 'dayrows-feature-1024x500.png'):
        source = root / 'icons' / name
        with source.open('rb') as src, (store_artwork / name).open('xb') as dst:
            shutil.copyfileobj(src, dst)
        metadata['outputs'].append({'filename': f'store-artwork/{name}',
                                    'sha256': hashlib.sha256((store_artwork / name).read_bytes()).hexdigest()})
    metadata['launcher_inputs'] = [
        {'filename': f'icons/{name}', 'sha256': hashlib.sha256((root / 'icons' / name).read_bytes()).hexdigest()}
        for name in ('dayrows-adaptive-foreground-432.png', 'dayrows-play-icon-512.png')
    ]
    metadata['status'] = 'success'
except Exception as error:
    metadata['status'] = 'failed'
    metadata['error'] = str(error)
    raise
finally:
    with manifest.open('x') as output:
        json.dump(metadata, output, indent=2)
        output.write('\n')
print(f'\nDebug APK: {apk}\nArchived APK: {target}\nBuild manifest: {manifest}')
print(f'Install: adb install -r "{target}"')
PY
