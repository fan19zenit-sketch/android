"""Local CLI. Environment contains paths, never a secret on the command line."""
import argparse
import json
import os
from pathlib import Path

from mobile_support.core import Store


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["inventory", "configure", "release", "withdraw", "reset", "backup"])
    parser.add_argument("--device")
    parser.add_argument("--json-file")
    parser.add_argument("--ttl", type=int, default=900)
    parser.add_argument("--destination")
    parser.add_argument("--package", default="com.example.photovchistotu")
    args = parser.parse_args()
    store = Store(os.environ["SUPPORT_DB"], os.environ["SUPPORT_REGISTRY"],
                  Path(os.environ["SUPPORT_SECRET_FILE"]).read_bytes())
    if args.command == "inventory":
        print(json.dumps(store.inventory(), ensure_ascii=False, indent=2))
    elif args.command == "configure":
        store.configure(args.device, json.loads(Path(args.json_file).read_text()), args.ttl)
    elif args.command == "release":
        store.publish(json.loads(Path(args.json_file).read_text()))
    elif args.command == "reset":
        store.reset(args.device)
    elif args.command == "backup":
        store.backup(args.destination)
    elif args.command == "withdraw":
        with store.db() as db:
            db.execute("DELETE FROM releases WHERE package=?", (args.package,))
            db.execute("INSERT INTO audit VALUES (?,?,?,?)", (int(store.clock()), "withdraw", args.package, "{}"))


if __name__ == "__main__":
    main()
