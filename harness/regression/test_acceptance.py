"""Synthetic database and acceptance-script regressions; never use the user's database."""
from contextlib import closing
import importlib.util
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('preservation', ROOT / 'tools/db-preservation.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def seed(db):
    with closing(sqlite3.connect(db)) as c, c:
        c.executescript('''
        CREATE TABLE Track(id TEXT PRIMARY KEY,title TEXT,artist TEXT,album TEXT,albumArtist TEXT,path TEXT,playCount INT);
        CREATE TABLE Playlist(id TEXT PRIMARY KEY,title TEXT);
        CREATE TABLE PlaylistTrack(playlistId TEXT,trackId TEXT,addedTime INT,"order" INT);
        INSERT INTO Track VALUES('local-1','private title','artist','album','artist','file:///local.flac',0);
        INSERT INTO Playlist VALUES('local-list','private playlist');
        INSERT INTO PlaylistTrack VALUES('local-list','local-1',1,0);
        ''')


class PreservationTests(unittest.TestCase):
    def test_owned_changes_allowed_local_changes_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / 'spw.db'
            seed(db)
            before = gate.snapshot(db)
            self.assertNotIn('private', json.dumps(before))
            with closing(sqlite3.connect(db)) as c, c:
                c.executescript("INSERT INTO Track VALUES('netease-1','online','','','','',0);"
                                "INSERT INTO Playlist VALUES('netease-list','online');"
                                "INSERT INTO PlaylistTrack VALUES('netease-list','netease-1',1,0);"
                                "UPDATE Track SET playCount=1 WHERE id='local-1';")
            self.assertEqual((0, 0, 0), gate.compare(before, gate.snapshot(db)))
            with closing(sqlite3.connect(db)) as c, c:
                c.execute("UPDATE Track SET title='damaged' WHERE id='local-1'")
            self.assertEqual(1, gate.compare(before, gate.snapshot(db))[0])

    def test_local_membership_orphans_and_duplicates(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / 'spw.db'
            seed(db)
            before = gate.snapshot(db)
            with closing(sqlite3.connect(db)) as c, c:
                c.executescript("DELETE FROM PlaylistTrack;"
                                "INSERT INTO PlaylistTrack VALUES('netease-missing','missing',1,0);"
                                "INSERT INTO PlaylistTrack VALUES('netease-missing','missing',1,0);")
            self.assertEqual((1, 2, 1), gate.compare(before, gate.snapshot(db)))

    def test_wal_is_included(self):
        with tempfile.TemporaryDirectory() as temp:
            db = Path(temp) / 'spw.db'
            seed(db)
            with closing(sqlite3.connect(db)) as c, c:
                c.execute('PRAGMA journal_mode=WAL')
                before = gate.snapshot(db)
                c.execute("DELETE FROM Track WHERE id='local-1'")
                c.commit()
                self.assertEqual(1, gate.compare(before, gate.snapshot(db))[0])

    def test_invalid_package_cannot_be_hidden_by_harness_success(self):
        project = json.loads((ROOT / 'project.json').read_text(encoding='utf-8-sig'))
        artifact = ROOT / 'build/dist' / f"plugin-{project['pluginId']}-{project['version']}.spmod"
        self.assertTrue(artifact.exists(), 'build the current package before tests')
        with tempfile.TemporaryDirectory() as temp:
            bad = Path(temp) / 'invalid.spmod'
            with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(bad, 'w') as output:
                for entry in source.infolist():
                    if entry.filename == 'classes/preference_config.json':
                        continue
                    output.writestr(entry, source.read(entry.filename))
            run = subprocess.run(['pwsh','-NoProfile','-File',str(ROOT/'harness/run-harness.ps1'),
                                  '-Spmod',str(bad)],capture_output=True)
            self.assertNotEqual(0, run.returncode)
            self.assertNotIn('HARNESS_RESULT=ALL_PASS', run.stdout.decode(errors='replace'))

    def test_script_version_mode_and_unknown_are_not_pass(self):
        project = json.loads((ROOT / 'project.json').read_text(encoding='utf-8-sig'))
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            seed(root / 'spw.db')
            evidence = root / 'evidence'
            plugin = root / 'workshop/plugins' / f"plugin-{project['pluginId']}-{project['version']}"
            plugin.mkdir(parents=True)
            (plugin / 'META-INF').mkdir()
            (plugin / 'META-INF/MANIFEST.MF').write_text(
                f"Plugin-Id: {project['pluginId']}\nPlugin-Version: {project['version']}\n", encoding='utf-8')
            for i in range(61):
                (plugin / str(i)).touch()
            args = ['pwsh', '-NoProfile', '-File', str(ROOT / 'tools/accept-host.ps1'),
                    '-DataRoot', str(root), '-EvidenceDir', str(evidence)]
            pre = subprocess.run(args + ['-Phase', 'Pre', '-SkipInstall'], capture_output=True)
            self.assertEqual(0, pre.returncode, pre.stdout.decode(errors='replace') + pre.stderr.decode(errors='replace'))
            logs = root / 'workshop/data' / project['pluginId'] / 'logs'
            logs.mkdir(parents=True)
            log = logs / 'synthetic.log'
            log.write_text('[INFO ] [plugin] 启动完成\n[INFO ] [caps] 能力矩阵\n'
                           '[INFO ] [config] dev_mode=false\n[INFO ] [plugin] 已停止\n', encoding='utf-8')
            post = subprocess.run(args + ['-Phase', 'Post'], capture_output=True)
            self.assertEqual(2, post.returncode, post.stderr.decode(errors='replace'))
            report = json.loads((evidence / 'report-post.json').read_text(encoding='utf-8-sig'))
            rows = {r['Id']: r['Verdict'] for r in report['rows']}
            self.assertEqual('PASS', rows['A1'])
            self.assertEqual('PASS', rows['A4'])
            self.assertEqual('PASS', rows['A6'])
            self.assertEqual('UNKNOWN', rows['V1'])
            with log.open('a', encoding='utf-8') as f:
                f.write('[INFO ] [toast] unexpected toast\n')
            bad = subprocess.run(args + ['-Phase', 'Post'], capture_output=True)
            self.assertEqual(1, bad.returncode, bad.stderr.decode(errors='replace'))


if __name__ == '__main__':
    unittest.main()
