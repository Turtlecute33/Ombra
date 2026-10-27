#!/bin/zsh
# Writes app/src/main/assets/licenses/rust-notices.txt: the copyright notices and licence texts of the
# Rust crates in libobscuravpn_client.so that need more than the Apache-2.0 text, and the NOTICE files
# of the other crates (OMBRA-UX-002). The Licences screen shows the file. Run it after each dependency
# change, after app/src/main/assets/licenses/rust-crates.txt is up to date: it stops when the crates
# differ from that list. Never downloads: it reads the crate sources that `cargo fetch --locked` put
# into $CARGO_HOME. Needs python3.
set -euo pipefail

readonly REPO_DIR=${0:A:h:h}
readonly RUSTLIB_DIR=$REPO_DIR/rust/obscura-core
readonly ASSETS_DIR=$REPO_DIR/app/src/main/assets/licenses
readonly TARGET=aarch64-linux-android

die() { print -u2 -- "error: $*"; exit 1 }

# Replaces dst only when the content changed, so its mtime stays stable for Gradle up-to-date checks.
install_if_changed() {
  local src=$1 dst=$2
  if cmp -s $src $dst; then rm -f $src; else mv -f $src $dst; fi
}

command -v python3 >/dev/null || die "python3 missing"
export RUSTUP_AUTO_INSTALL=0
tmp=$(mktemp -d)
trap 'rm -rf $tmp' EXIT

# cargo reads rust-toolchain.toml from the working dir.
cd $RUSTLIB_DIR
# The crates in the library: the normal dependencies for the target. Build scripts, build tools and
# proc-macro crates are not in it. Same rule as rust-crates.txt.
cargo tree -e normal,no-proc-macro --target $TARGET --prefix none --format '{p}' --locked --offline \
  | sed 's/ (\*)$//' | sort -u > $tmp/tree.txt
cargo metadata --format-version 1 --locked --offline --filter-platform $TARGET > $tmp/metadata.json

python3 - $tmp/tree.txt $tmp/metadata.json $ASSETS_DIR/rust-crates.txt $tmp/rust-notices.txt <<'EOF'
import json, os, re, sys, textwrap
from collections import defaultdict

tree_path, metadata_path, crates_path, out_path = sys.argv[1:]

def die(message):
    sys.exit(f"error: {message}")

# The Licences screen shows these texts on their own pages.
SHOWN = {"Apache-2.0", "GPL-3.0-only WITH GPL-3.0-linking-source-exception"}
LICENCE_FILE = re.compile(r"(?i)^(licen[cs]e|copying|copyright|notice|unlicense)")
CARGO_HOME = os.path.realpath(os.environ.get("CARGO_HOME") or os.path.expanduser("~/.cargo"))
CHECKOUTS = os.path.join(CARGO_HOME, "git", "checkouts")

def options(expression):
    """The top-level AND terms of an SPDX expression, each as its list of OR options."""
    tokens = re.findall(r"\(|\)|[^\s()]+", expression.replace("/", " OR "))
    terms, current, depth = [], [], 0
    for token in tokens:
        if token == "(":
            depth += 1
        elif token == ")":
            depth -= 1
        elif token == "AND" and depth == 0:
            terms.append(current)
            current = []
        elif current and current[-1] == "WITH":
            current[-2:] = [f"{current[-2]} WITH {token}"]
        else:
            current.append(token)
    terms.append(current)
    return [[t for t in term if t != "OR"] for term in terms]

def needs_notice(package):
    expression = package["license"]
    if expression is None:
        return True
    # An AND inside brackets is not taken apart: such a term always gets the texts.
    return any("AND" in term or not SHOWN.intersection(term) for term in options(expression))

def source_root(crate_dir):
    """A git dependency can keep its licence at the root of its repository checkout."""
    relative = os.path.relpath(crate_dir, CHECKOUTS)
    if relative.startswith(".."):
        return crate_dir
    return os.path.join(CHECKOUTS, *relative.split(os.sep)[:2])

def licence_files(package):
    crate_dir = os.path.realpath(os.path.dirname(package["manifest_path"]))
    if package.get("license_file"):
        return [os.path.join(crate_dir, package["license_file"])]
    directory, root = crate_dir, source_root(crate_dir)
    while True:
        names = sorted(
            n for n in os.listdir(directory) if LICENCE_FILE.match(n) and os.path.isfile(os.path.join(directory, n))
        )
        if names or directory == root:
            return [os.path.join(directory, n) for n in names]
        directory = os.path.dirname(directory)

def where(path, crate_dir):
    relative = os.path.relpath(path, crate_dir)
    return f"{os.path.basename(path)} at the root of its repository" if relative.startswith("..") else relative

def read_text(path):
    with open(path, encoding="utf-8") as f:
        lines = [line.rstrip() for line in f.read().replace("\r\n", "\n").split("\n")]
    return "\n".join(lines).strip("\n")

metadata = json.load(open(metadata_path))
packages = defaultdict(list)
for package in metadata["packages"]:
    packages[(package["name"], package["version"])].append(package)

crates = []
for line in open(tree_path).read().splitlines():
    name, version = line.split(" ")[:2]
    found = packages[(name, version.removeprefix("v"))]
    if len(found) != 1:
        die(f"{name} {version}: {len(found)} packages in cargo metadata")
    crates.append(found[0])
crates.sort(key=lambda p: (p["name"], p["version"]))

listed = open(crates_path).read().split("name version — license — repository\n", 1)[1]
listed = {tuple(line.split(" — ")[0].split(" ")) for line in listed.splitlines() if line.strip()}
in_library = {(p["name"], p["version"]) for p in crates}
if in_library != listed:
    die(f"the crates differ from rust-crates.txt; make it again first. Only in the library: "
        f"{sorted(in_library - listed)}; only in the list: {sorted(listed - in_library)}")

# The Apache-2.0 text has its own page, so a crate copy of it is left out.
files = {}
for package in crates:
    found = [f for f in licence_files(package) if "apache" not in os.path.basename(f).lower()]
    if not needs_notice(package):
        found = [f for f in found if os.path.basename(f).upper().startswith("NOTICE")]
    files[(package["name"], package["version"])] = found

groups = {}
for package in crates:
    key = (package["name"], package["version"])
    if not needs_notice(package) and not files[key]:
        continue
    label, paths = f"{package['name']} {package['version']}", files[key]
    if paths:
        crate_dir = os.path.realpath(os.path.dirname(package["manifest_path"]))
        labels = [f"{label} ({where(p, crate_dir)})" for p in paths]
    else:
        # Some crates do not package their licence file: use the one of a crate from the same repository.
        donor = next((p for p in crates if package["repository"] and p["repository"] == package["repository"]
                      and files[(p["name"], p["version"])]), None)
        if donor is None:
            die(f"{label} ({package['license']}): no licence file in its source; add its text by hand")
        paths = files[(donor["name"], donor["version"])]
        labels = [f"{label} ({os.path.basename(p)} of {donor['name']} {donor['version']}, same repository)" for p in paths]
    for path, entry in zip(paths, labels):
        groups.setdefault(read_text(path), []).append(entry)

header = """Licence texts of the Rust crates in libobscuravpn_client.so, the Obscura VPN core of Ombra

Each crate here has a licence, or a part of a licence, with no Apache-2.0 option. Such licences ask for their copyright notice and licence text in each copy. A crate that Apache-2.0 covers fully is here only when it has a NOTICE file. The Apache-2.0 and GPL-3.0 texts have their own pages, and the page "Rust crate licences" lists all crates. Each identical text is here once, after the names of the crates that use it.

Made by scripts/rust-notices.zsh from the crate sources."""

def wrap(text):
    return "\n".join(textwrap.fill(p, 70, break_on_hyphens=False) for p in text.split("\n"))

def heading(entries):
    """The crates of one text, 70 columns wide; a line breaks only between two crates."""
    lines = ["==>"]
    for i, entry in enumerate(entries):
        piece = entry + ("," if i < len(entries) - 1 else "")
        if len(lines[-1]) + 1 + len(piece) > 70 and lines[-1] != "==>":
            lines.append("   ")
        lines[-1] += " " + piece
    return "\n".join(lines)

parts = [wrap(header)]
for text, entries in groups.items():
    parts.append(heading(entries))
    parts.append(text)
with open(out_path, "w", encoding="utf-8") as out:
    out.write("\n\n".join(parts) + "\n")
print(f"{sum(len(e) for e in groups.values())} licence files of {len(in_library)} crates, {len(groups)} different texts")
EOF

install_if_changed $tmp/rust-notices.txt $ASSETS_DIR/rust-notices.txt
print -- "${ASSETS_DIR#$REPO_DIR/}/rust-notices.txt: $(stat -f %z $ASSETS_DIR/rust-notices.txt) bytes"
