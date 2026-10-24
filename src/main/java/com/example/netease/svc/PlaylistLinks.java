package com.example.netease.svc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local playlist compatibility for the host's v35 SyncTrack migration.
 * The SQL and stable identities follow the installed host's v34->35 migration.
 * Cloud scopes/merge data are outside the plugin's write contract. */
final class PlaylistLinks {
    private final NativeLibrary.SqlSchema sql;
    final boolean modern;

    PlaylistLinks(NativeLibrary.SqlSchema sql) throws Exception {
        this.sql = sql;
        List<Object[]> columns = sql.rows("PRAGMA table_info('PlaylistTrack')");
        modern = columns.stream().anyMatch(r -> "syncTrackId".equals(r[1]));
        if (!modern && columns.stream().noneMatch(r -> "trackId".equals(r[1])))
            throw new IllegalStateException("不支持的歌单关联表结构，未开始写入");
        if (modern && !sql.rows("SELECT 1 FROM SyncScope WHERE id='local'").isEmpty()
                && sql.rows("SELECT 1 FROM SyncScope WHERE id='local' AND provider='None' AND status='LocalOnly'").isEmpty())
            throw new IllegalStateException("宿主本地歌单作用域不可用，未开始写入");
    }

    /** A fresh v35 database has no local scope until the host first creates a playlist. */
    void ensureLocalScope() throws Exception {
        if (!modern) return;
        // Values verified against the host's v34 -> v35 migration. Never replace an
        // existing scope or touch remote provider/account/merge metadata.
        sql.exec("INSERT INTO SyncScope (id,provider,status,containerIdentifier,environment,userRecordName,datasetId,recordZoneName,sessionId,engineStateData,lastSequenceNumber,initialSyncPhase,initialSyncCursor,blockingReason) "
                + "VALUES ('local','None','LocalOnly','','NotConfigured','','','','',X'',0,'NotStarted','','None') ON CONFLICT(id) DO NOTHING");
        if (sql.rows("SELECT 1 FROM SyncScope WHERE id='local' AND provider='None' AND status='LocalOnly'").isEmpty())
            throw new IllegalStateException("宿主本地歌单作用域不可用，未开始写入");
    }

    void checkPlaylist(String id) throws Exception {
        if (!modern) return;
        if (!sql.rows("SELECT 1 FROM Playlist WHERE id=? AND (scopeId!='local' OR length(mergeStateData)>0)", id).isEmpty())
            throw new IllegalStateException("该歌单含宿主云同步状态，拒绝覆盖：" + id);
        if (!sql.rows("SELECT 1 FROM PlaylistTrack WHERE playlistId=? AND (scopeId!='local' OR length(mergeStateData)>0 OR length(positionKey)>0)", id).isEmpty())
            throw new IllegalStateException("该歌单含宿主合并状态，拒绝重建：" + id);
    }

    void playlistValues(String id, Map<String,Object> values) {
        if (!modern) return;
        values.put("scopeId", "local");
        values.put("syncId", stable("urn:com.xuncorp:sync:legacy-v34:playlist:" + id));
        values.put("positionKey", new byte[0]);
        values.put("mergeStateData", new byte[0]);
    }

    void insert(String playlist, String track, int order, long now) throws Exception {
        // Migrations retain local Track ids as SyncTrack ids. Do not rebind a
        // host-merged identity or overwrite favorite/merge/binding metadata.
        if (!sql.rows("SELECT 1 FROM SyncTrack WHERE id=? AND (scopeId!='local' OR primaryTrackId IS NOT ? OR playbackTrackId IS NOT ? OR length(mergeStateData)>0 OR length(bindingEvidenceData)>0)",
                track, track, track).isEmpty())
            throw new IllegalStateException("曲目含宿主合并或不同播放绑定，拒绝覆盖：" + track);
        sql.exec("INSERT INTO SyncTrack (id,scopeId,syncId,title,artist,album,albumArtist,genre,year,number,isFavorite,audioHash,duration,primaryTrackId,playbackTrackId,bindingEvidenceData,mergeStateData) "
                + "SELECT id,'local',?,title,artist,album,albumArtist,genre,year,number,isFavorite,audioHash,duration,id,id,X'',X'' FROM Track WHERE id=? "
                + "ON CONFLICT(id) DO UPDATE SET title=excluded.title,artist=excluded.artist,album=excluded.album,albumArtist=excluded.albumArtist,duration=excluded.duration",
                stable("urn:com.xuncorp:sync:legacy-v34:entry:" + track), track);
        List<Object[]> ids = sql.rows("SELECT p.syncId,s.syncId FROM Playlist p JOIN SyncTrack s ON s.scopeId=p.scopeId WHERE p.id=? AND p.scopeId='local' AND s.id=?", playlist, track);
        if (ids.size()!=1) throw new IllegalStateException("歌单与曲目本地绑定未建立");
        String memberId = stable("urn:com.xuncorp:sync:member-v1:" + ids.getFirst()[0] + ":" + ids.getFirst()[1]);
        int n = sql.execUpdate("INSERT INTO PlaylistTrack (playlistId,syncTrackId,addedTime,`order`,scopeId,syncId,isRemoved,positionKey,mergeStateData) "
                + "VALUES (?,?,?,?,'local',?,0,X'',X'') ON CONFLICT(playlistId,syncTrackId) DO UPDATE SET `order`=excluded.`order`,isRemoved=0",
                playlist, track, now, order, memberId);
        if (n!=1) throw new IllegalStateException("歌单关联写入未生效");
    }

    String missingTrack() {
        return modern
                ? "pt.scopeId='local' AND pt.isRemoved=0 AND NOT EXISTS (SELECT 1 FROM SyncTrack s JOIN Track t ON t.id=s.playbackTrackId WHERE s.scopeId=pt.scopeId AND s.id=pt.syncTrackId)"
                : "NOT EXISTS (SELECT 1 FROM Track t WHERE t.id=pt.trackId)";
    }

    /** RFC 4122 UUID v5, URL namespace; identical to the host's SyncIds. */
    static String stable(String name) {
        try {
            var namespace = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).array());
            byte[] value = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            value[6]=(byte)((value[6]&15)|80); value[8]=(byte)((value[8]&63)|128);
            var bytes=ByteBuffer.wrap(value);
            return new UUID(bytes.getLong(),bytes.getLong()).toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
