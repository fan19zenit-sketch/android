"""Server-local, consistent SQLite + authentication secret backup, seven days."""
import os
import tarfile
import tempfile
import time
from pathlib import Path

from mobile_support.core import Store


def main():
    os.umask(0o077)
    root = Path(os.environ["SUPPORT_DB"]).parent
    destination = root / "backups"
    destination.mkdir(mode=0o700, exist_ok=True)
    store = Store(os.environ["SUPPORT_DB"], os.environ["SUPPORT_REGISTRY"],
                  Path(os.environ["SUPPORT_SECRET_FILE"]).read_bytes())
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    target = destination / f"support-{stamp}.tar.gz"
    with tempfile.TemporaryDirectory(dir=root) as temporary:
        database = Path(temporary) / "support.sqlite3"
        store.backup(database)
        with tarfile.open(target.with_suffix(".part"), "w:gz") as archive:
            archive.add(database, arcname="support.sqlite3")
            archive.add(os.environ["SUPPORT_SECRET_FILE"], arcname="secret")
        target.with_suffix(".part").replace(target)
    for old in destination.glob("support-*.tar.gz"):
        if old.stat().st_mtime < time.time() - 7 * 86400:
            old.unlink()
    print("Support backup complete")


if __name__ == "__main__":
    main()
