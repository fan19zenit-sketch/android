"""Exercise the published signed APK upgrade on a disposable, network-isolated emulator."""
from __future__ import annotations

from collections import Counter
from contextlib import closing
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time


PACKAGE = 'com.example.photovchistotu'
DEVICE = 'upgrade-fixture-device'
CITY = 'Upgrade Fixture'
IDS = ('aaaaaaaa-1111-4111-8111-111111111111', 'bbbbbbbb-2222-4222-8222-222222222222',
       'cccccccc-3333-4333-8333-333333333333')
HISTORY_ID = 'dddddddd-4444-4444-8444-444444444444'
APP_ROOT = '/data/user/0/' + PACKAGE
DB = APP_ROOT + '/databases/pilot-photo-db'
PREFS = APP_ROOT + '/shared_prefs/app_prefs.xml'
PHOTO_CONTENTS = {item: ('synthetic-photo-' + item).encode() for item in (*IDS, HISTORY_ID)}


def command(*args, check=True):
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    if check and result.returncode:
        raise RuntimeError(f'{args[0]} failed: {result.stdout[-1500:]}')
    return result.stdout.strip()


def adb(*args, check=True):
    return command('adb', '-s', SERIAL, *args, check=check)


class Fixture(BaseHTTPRequestHandler):
    received: list[tuple[str, str, bytes, bool]] = []
    lock = threading.Lock()

    def log_message(self, *_args):
        pass

    def respond(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != '/upload-v2':
            self.respond(404, {})
            return
        length = int(self.headers.get('Content-Length', '0'))
        if not 0 < length < 1_000_000:
            self.respond(400, {})
            return
        content = self.rfile.read(length)
        header = ('Content-Type: ' + self.headers.get('Content-Type', '') + '\r\nMIME-Version: 1.0\r\n\r\n').encode()
        message = BytesParser(policy=default).parsebytes(header + content)
        fields = {part.get_param('name', header='content-disposition'): part.get_payload(decode=True)
                  for part in message.iter_parts()}
        device = (fields.get('deviceUuid') or b'').decode()
        upload = (fields.get('clientUploadId') or b'').decode()
        with self.lock:
            self.received.append((device, upload, fields.get('photo') or b'', 'photoContext' in fields))
        self.respond(202, {'job': {'job_id': 'fixture-' + upload}})

    def do_GET(self):
        if self.path.startswith('/upload-jobs/fixture-'):
            job = self.path.removeprefix('/upload-jobs/')
            self.respond(200, {'job': {'job_id': job, 'status': 'sent', 'message_id': 'fixture-mid',
                                      'sent_at': '2026-11-10T09:00:00Z'}})
        else:
            self.respond(404, {})


def pull_database(destination):
    for suffix in ('', '-wal', '-shm'):
        result = subprocess.run(['adb', '-s', SERIAL, 'shell', 'test', '-f', DB + suffix], timeout=10)
        if result.returncode == 0:
            adb('pull', DB + suffix, str(destination) + suffix)
        elif suffix == '':
            raise RuntimeError('old application did not initialize its queue database')


def push_owned(source, destination, uid):
    temporary = '/data/local/tmp/photobot-upgrade-fixture'
    adb('push', str(source), temporary)
    adb('shell', 'cp', temporary, destination)
    adb('shell', 'chown', f'{uid}:{uid}', destination)
    adb('shell', 'chmod', '600', destination)
    adb('shell', 'restorecon', destination)


def rows(path):
    with closing(sqlite3.connect(path)) as db:
        columns = {row[1] for row in db.execute('PRAGMA table_info(photo_records)')}
        required = {'id', 'photoPath', 'status', 'queuedInUploadQueue'}
        if not required <= columns:
            raise RuntimeError('unexpected queue schema')
        return db.execute('SELECT id,photoPath,status,queuedInUploadQueue FROM photo_records ORDER BY id').fetchall()


def main(old_apk, candidate):
    global SERIAL
    listed = command('adb', 'devices').splitlines()[1:]
    devices = [line.split()[0] for line in listed if line.endswith('\tdevice')]
    if len(devices) != 1 or not devices[0].startswith('emulator-'):
        raise RuntimeError('exactly one explicitly isolated emulator is required')
    SERIAL = devices[0]
    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise RuntimeError('refusing to run on a physical device')
    adb('root')
    adb('wait-for-device')
    if adb('shell', 'id', '-u') != '0':
        raise RuntimeError('rooted disposable emulator required for network isolation')
    for tool in ('iptables', 'ip6tables'):
        adb('shell', tool, '-w', '30', '-I', 'OUTPUT', '1', '-j', 'REJECT')
        adb('shell', tool, '-w', '30', '-I', 'OUTPUT', '1', '-o', 'lo', '-j', 'ACCEPT')
    adb('reverse', 'tcp:18433', 'tcp:18433')
    adb('install', '-r', old_apk)
    uid = adb('shell', 'stat', '-c', '%u', APP_ROOT)
    if not uid.isdigit():
        raise RuntimeError('cannot identify fixture application owner')

    with tempfile.TemporaryDirectory(prefix='signed-upgrade-') as temp:
        root = Path(temp)
        prefs = root / 'app_prefs.xml'
        prefs.write_text("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
                         f'<string name="backend_url">http://127.0.0.1:18433</string>\n'
                         f'<string name="city">{CITY}</string>\n<string name="device_uuid">{DEVICE}</string>\n'
                         '<string name="transport_type">max</string>\n<string name="fixture_setting">preserve-me</string>\n</map>\n', encoding='utf8')
        adb('shell', 'mkdir', '-p', APP_ROOT + '/shared_prefs')
        adb('shell', 'chown', f'{uid}:{uid}', APP_ROOT + '/shared_prefs')
        push_owned(prefs, PREFS, uid)
        adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.CAMERA')
        adb('shell', 'am', 'start', '-n', PACKAGE + '/com.example.simplephotouploader.MainActivity')
        for _ in range(40):
            if subprocess.run(['adb', '-s', SERIAL, 'shell', 'test', '-f', DB], timeout=10).returncode == 0:
                break
            time.sleep(.5)
        adb('shell', 'am', 'force-stop', PACKAGE)
        old_db = root / 'old-db'
        pull_database(old_db)
        with closing(sqlite3.connect(old_db)) as db:
            db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
            db.execute('PRAGMA journal_mode=DELETE')
            for index, item in enumerate((*IDS, HISTORY_ID)):
                photo_path = APP_ROOT + '/files/' + item + '.jpg'
                db.execute('''INSERT INTO photo_records (id,jobId,messageId,chatDeleted,photoPath,thumbnailPath,
                    capturedAt,sentAt,status,error,queuedInUploadQueue,manualRetryApproved)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)''',
                    (item, 'old-job' if item == HISTORY_ID else None, 'old-mid' if item == HISTORY_ID else None,
                     0, photo_path, None, f'2026-11-07T09:0{index}:00Z',
                     '2026-11-07T10:00:00Z' if item == HISTORY_ID else None,
                     'sent' if item == HISTORY_ID else 'queued', None, 0 if item == HISTORY_ID else 1, 1))
                photo = root / (item + '.jpg')
                photo.write_bytes(PHOTO_CONTENTS[item])
                push_owned(photo, photo_path, uid)
            db.commit()
        adb('shell', 'rm', '-f', DB + '-wal', DB + '-shm')
        push_owned(old_db, DB, uid)
        expected = rows(old_db)
        adb('install', '-r', candidate)
        after = root / 'after-install-db'
        pull_database(after)
        if rows(after) != expected:
            raise RuntimeError('queue or history changed during installation')
        saved_prefs = root / 'after-prefs.xml'
        adb('pull', PREFS, str(saved_prefs))
        for value in (CITY, DEVICE, 'preserve-me', 'http://127.0.0.1:18433'):
            if value not in saved_prefs.read_text(encoding='utf8'):
                raise RuntimeError('branch, UUID or settings were not preserved')
        for item, expected_bytes in PHOTO_CONTENTS.items():
            copy = root / ('saved-' + item)
            adb('pull', APP_ROOT + '/files/' + item + '.jpg', str(copy))
            if hashlib.sha256(copy.read_bytes()).digest() != hashlib.sha256(expected_bytes).digest():
                raise RuntimeError('fixture photo changed during installation')
        server = ThreadingHTTPServer(('127.0.0.1', 18433), Fixture)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            adb('shell', 'am', 'start', '-n', PACKAGE + '/com.example.simplephotouploader.MainActivity')
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                with Fixture.lock:
                    if len(Fixture.received) >= len(IDS):
                        break
                time.sleep(2)
            time.sleep(8)
            with Fixture.lock:
                received = list(Fixture.received)
            if Counter(item[1] for item in received) != Counter(IDS):
                raise RuntimeError('queue was not delivered exactly once: ' + str(Counter(item[1] for item in received)))
            for device, upload, photo, has_context in received:
                if device != DEVICE or photo != PHOTO_CONTENTS[upload] or has_context:
                    raise RuntimeError('delivery changed the photo, device or old author metadata')
            adb('shell', 'am', 'force-stop', PACKAGE)
            final = root / 'final-db'
            pull_database(final)
            with closing(sqlite3.connect(final)) as db:
                saved = db.execute('SELECT id,photoContext FROM photo_records WHERE id IN (?,?,?,?)', (*IDS, HISTORY_ID)).fetchall()
                if len(saved) != 4 or any(context is not None for _, context in saved):
                    raise RuntimeError('old photos acquired cook metadata or history was lost')
            print('SIGNED_UPGRADE_OK: branch, UUID, settings, four files and history preserved')
            print('ISOLATED_DELIVERY_OK: three queued photos delivered once each without cook metadata')
        finally:
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('expected old and candidate signed APKs')
    main(sys.argv[1], sys.argv[2])
