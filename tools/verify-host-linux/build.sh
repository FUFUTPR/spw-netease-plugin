#!/bin/sh
set -eu
cd "$(dirname "$0")"
: "${JAVA_HOME:?Set JAVA_HOME to the Linux host JDK}"
out=${1:-../../build/verify-host-linux}
mkdir -p "$out"
flags=$(pkg-config --cflags --libs gtk+-3.0 webkit2gtk-4.1 json-glib-1.0 x11)
cc -std=c11 -O2 -Wall -Wextra -Werror -Wno-deprecated-declarations main.c -o "$out/VerifyHost" $flags
cc -std=c11 -O2 -Wall -Wextra -Werror -shared -fPIC surface.c -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -ldl -o "$out/libverify-surface.so"
python3 - "$out" <<'PY'
import hashlib, json, pathlib, sys
out=pathlib.Path(sys.argv[1])
digest=lambda p: hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
info={'format':1,'source_sha256':{p:digest(p) for p in ('main.c','surface.c')},
      'binary_sha256':{p:digest(out/p) for p in ('VerifyHost','libverify-surface.so')}}
(out/'build-info.json').write_text(json.dumps(info,indent=2)+'\n',encoding='utf-8')
PY
