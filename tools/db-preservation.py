"""Read-only SQLite preservation gate. Snapshots contain hashes, never song metadata.

snapshot DB OUT | check DB BASELINE: exit 0 PASS, 1 FAIL, 2 UNKNOWN.
Host-owned playback counters and derived artwork are excluded from Track comparison.
Run against a quiet host or a database copy to avoid unrelated user edits.
"""
from contextlib import closing
import hashlib
import json
import sqlite3
import sys
from pathlib import Path


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, default=str,
                                     separators=(',', ':')).encode()).hexdigest()


def snapshot(db):
    with closing(sqlite3.connect(Path(db).resolve().as_uri() + '?mode=ro', uri=True)) as con:
        con.execute('BEGIN')  # one consistent view, including WAL
        definitions = {
            'Track': (['id', 'title', 'artist', 'album', 'albumArtist', 'path'],
                      "id NOT LIKE 'netease-%'"),
            'Playlist': (['id', 'title'], "id NOT LIKE 'netease-%'"),
            'PlaylistTrack': (['playlistId', 'trackId', 'addedTime', 'order'],
                              "playlistId NOT LIKE 'netease-%'"),
        }
        result = {'schema': 1, 'tables': {}}
        for table, (columns, where) in definitions.items():
            actual = {r[1] for r in con.execute(f'PRAGMA table_info("{table}")')}
            if not set(columns) <= actual:
                raise ValueError(f'unsupported schema: {table}')
            quoted = ','.join('"' + c + '"' for c in columns)
            rows = con.execute(f'SELECT {quoted} FROM "{table}" WHERE {where}').fetchall()
            # A multiset preserves duplicate local associations too.
            counts = {}
            for row in rows:
                key = digest(row)
                counts[key] = counts.get(key, 0) + 1
            result['tables'][table] = counts
        result['orphans'] = con.execute(
            "SELECT count(*) FROM PlaylistTrack p WHERE p.playlistId LIKE 'netease-%' "
            'AND (NOT EXISTS (SELECT 1 FROM Track t WHERE t.id=p.trackId) '
            'OR NOT EXISTS (SELECT 1 FROM Playlist l WHERE l.id=p.playlistId))').fetchone()[0]
        result['duplicates'] = con.execute(
            'SELECT count(*) FROM (SELECT playlistId,trackId FROM PlaylistTrack '
            "WHERE playlistId LIKE 'netease-%' GROUP BY playlistId,trackId HAVING count(*)>1)").fetchone()[0]
        return result


def compare(before, after):
    if before.get('schema') != 1 or set(before.get('tables', {})) != {'Track', 'Playlist', 'PlaylistTrack'}:
        raise ValueError('unsupported baseline')
    missing = sum(max(0, count - after['tables'][table].get(key, 0))
                  for table, rows in before['tables'].items() for key, count in rows.items())
    # Existing orphan rows are reported separately; reject newly introduced corruption.
    return missing, max(0, after['orphans'] - before['orphans']), after['duplicates']


def main():
    try:
        mode, db, path = sys.argv[1:]
        if mode not in ('snapshot', 'check'):
            raise ValueError('expected snapshot or check')
        current = snapshot(db)
        if mode == 'snapshot':
            Path(path).write_text(json.dumps(current), encoding='utf-8')
            print('PASS database baseline captured (hashes only)')
            return 0
        before = json.loads(Path(path).read_text(encoding='utf-8'))
        missing, orphans, duplicates = compare(before, current)
        failed = missing or orphans or duplicates
        print(f'{"FAIL" if failed else "PASS"} local records removed/changed={missing}, '
              f'new plugin orphans={orphans}, plugin duplicate pairs={duplicates}')
        return int(bool(failed))
    except (OSError, sqlite3.Error, ValueError, TypeError, KeyError):
        print('UNKNOWN database/baseline unavailable or unsupported; no preservation evidence')
        return 2


if __name__ == '__main__':
    sys.exit(main())
