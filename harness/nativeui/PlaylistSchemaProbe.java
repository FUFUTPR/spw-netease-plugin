package com.example.netease.svc;

import com.example.netease.net.Dto;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Production writer against schema-only copies; never opens the user's DB. */
public final class PlaylistSchemaProbe {
    static int checks;
    static void check(boolean ok,String name) { if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS "+name); }
    static long value(NativeLibrary.SqlSchema sql,String query)throws Exception {return ((Number)sql.rows(query).getFirst()[0]).longValue();}
    static void neighborQueries(Connection connection,NativeLibrary.SqlSchema sql,boolean modern)throws Exception {
        var playlist=NextTrack.class.getDeclaredMethod("playlistOf",Connection.class,long.class);playlist.setAccessible(true);
        var order=NextTrack.class.getDeclaredMethod("orderOf",Connection.class,long.class);order.setAccessible(true);
        check(playlist.invoke(null,connection,1002L).equals(1L),"neighbor fallback resolves actual track binding");
        check(order.invoke(null,connection,1L).equals(List.of(1001L,1002L)),"neighbor fallback returns playlist order");
        check(playlist.invoke(null,connection,9999L).equals(0L),"non-member search song has no neighbor without SQL error");
        if(modern) {
            sql.exec("UPDATE PlaylistTrack SET isRemoved=1 WHERE syncTrackId='netease-1002'");
            check(playlist.invoke(null,connection,1002L).equals(0L),"neighbor lookup excludes removed memberships");
            check(order.invoke(null,connection,1L).equals(List.of(1001L)),"neighbor sequence excludes removed memberships");
            sql.exec("UPDATE PlaylistTrack SET isRemoved=0 WHERE syncTrackId='netease-1002'");
        }
    }
    static void write(Connection connection,NativeLibrary.SqlSchema sql,List<NativeLibrary.Entry> entries)throws Exception {
        connection.setAutoCommit(false);
        try { NativeLibrary.writeOn(sql,entries,"http://127.0.0.1:1234/song/");connection.commit(); }
        catch(Exception failure) { connection.rollback();throw failure; }
        finally { connection.setAutoCommit(true); }
    }
    static void incremental(NativeLibrary.SqlSchema sql,boolean modern,Dto.Song a)throws Exception {
        String col=modern?"syncTrackId":"trackId";
        long tracks=value(sql,"SELECT count(*) FROM Track"), links=value(sql,"SELECT count(*) FROM PlaylistTrack");
        sql.exec("UPDATE Track SET isFavorite=1,playCount=23 WHERE id='netease-1001'");
        sql.exec("UPDATE Playlist SET `order`=27,trackSort=3,title='custom' WHERE id='netease-pl-2'");
        NativeLibrary.editMembershipOn(sql,2,"do not overwrite",a,false,"http://127.0.0.1:1234/song/");
        check(value(sql,"SELECT count(*) FROM PlaylistTrack")==links,"incremental duplicate add is idempotent");
        check(value(sql,"SELECT `order` FROM Playlist WHERE id='netease-pl-2'")==27 && sql.rows("SELECT title FROM Playlist WHERE id='netease-pl-2'").getFirst()[0].equals("custom"),"incremental edit preserves host playlist layout and title");
        NativeLibrary.editMembershipOn(sql,1,"",a,true,"");
        check(value(sql,"SELECT count(*) FROM PlaylistTrack WHERE playlistId='netease-pl-1' AND "+col+"='netease-1001'")==0,"incremental removal removes only the chosen membership");
        check(value(sql,"SELECT count(*) FROM PlaylistTrack WHERE playlistId='netease-pl-2' AND "+col+"='netease-1001'")==1 && value(sql,"SELECT count(*) FROM Track")==tracks,"removal keeps other playlists and track data");
        check(NativeLibrary.membershipsOn(sql,1001).equals(List.of(2L)),"All Songs and playbar discover actual memberships");
        NativeLibrary.editMembershipOn(sql,1,"",a,false,"http://127.0.0.1:1234/song/");
        check(value(sql,"SELECT isFavorite FROM Track WHERE id='netease-1001'")==1 && value(sql,"SELECT playCount FROM Track WHERE id='netease-1001'")==23,"remove/add preserves favorites and play count");
        var fresh=new Dto.Song(1003,"new cloud song","artist","album",180000,0,true,"");
        NativeLibrary.editMembershipOn(sql,3,"new playlist",fresh,false,"http://127.0.0.1:1234/song/");
        check(value(sql,"SELECT count(*) FROM PlaylistTrack WHERE playlistId='netease-pl-3'")==1,"new search song is immediately linked in newly created host playlist");
        check(NativeLibrary.membershipsOn(sql,1003).equals(List.of(3L)),"new membership is readable immediately without full sync");
        check(sql.rows("PRAGMA foreign_key_check").isEmpty(),"incremental edits satisfy actual foreign keys");
        sql.exec("DELETE FROM Playlist WHERE id='netease-pl-3'");
        if(modern) sql.exec("DELETE FROM SyncTrack WHERE id='netease-1003'");
        sql.exec("DELETE FROM Track WHERE id='netease-1003'");
    }
    public static void main(String[] args)throws Exception {
        Class.forName("org.sqlite.JDBC");
        var ctor=Class.forName("com.example.netease.svc.NativeLibrary$JdbcSql").getDeclaredConstructor(Connection.class);ctor.setAccessible(true);
        try(var connection=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var sql=(NativeLibrary.SqlSchema)ctor.newInstance(connection);
            sql.exec("PRAGMA foreign_keys=ON");
            boolean legacy=args.length>1&&args[1].equals("legacy");
            for(String statement:Files.readString(Path.of(args[0])).split(";"))
                if(!statement.isBlank()&&(!legacy||statement.contains("CREATE TABLE `Track`")))sql.exec(statement);
            if(legacy) {
                sql.exec("CREATE TABLE Playlist(id TEXT PRIMARY KEY,title TEXT NOT NULL,coverModifiedTime INTEGER NOT NULL,isUserEditedCover INTEGER NOT NULL,description TEXT NOT NULL,createdTime INTEGER NOT NULL,`order` INTEGER NOT NULL,trackSort INTEGER NOT NULL,trackSortDescending INTEGER NOT NULL)");
                sql.exec("CREATE TABLE PlaylistTrack(playlistId TEXT NOT NULL REFERENCES Playlist(id) ON DELETE CASCADE,trackId TEXT NOT NULL REFERENCES Track(id) ON DELETE CASCADE,addedTime INTEGER NOT NULL,`order` INTEGER NOT NULL,PRIMARY KEY(playlistId,trackId))");
            } else sql.exec("INSERT INTO SyncScope VALUES ('local','None','LocalOnly','','NotConfigured','','','','',X'',0,'NotStarted','','None')");
            var a=new Dto.Song(1001,"fixture A","artist","album",180000,0,true,"");
            var b=new Dto.Song(1002,"fixture B","artist","album",190000,0,true,"");
            var entries=List.of(new NativeLibrary.Entry(1,"playlist A",List.of(a,b)),new NativeLibrary.Entry(2,"playlist B",List.of(a)));
            write(connection,sql,entries);
            check(value(sql,"SELECT count(*) FROM Track")==2,"two distinct tracks");
            neighborQueries(connection,sql,!legacy);
            if(legacy) {
                check(value(sql,"SELECT count(*) FROM PlaylistTrack pt JOIN Track t ON t.id=pt.trackId")==3,"legacy host join returns all memberships");
                write(connection,sql,entries);
                check(value(sql,"SELECT count(*) FROM PlaylistTrack")==3,"legacy repeat synchronization retains shared songs");
                check(sql.rows("PRAGMA foreign_key_check").isEmpty(),"legacy foreign keys remain valid");
                incremental(sql,false,a);
                System.out.println("LEGACY_PLAYLIST_SCHEMA_RESULT="+checks+" PASS");return;
            }
            check(value(sql,"SELECT count(*) FROM SyncTrack")==2,"two host sync bindings");
            String joined="SELECT count(*) FROM PlaylistTrack pt JOIN SyncTrack s ON s.scopeId=pt.scopeId AND s.id=pt.syncTrackId JOIN Track t ON t.id=s.playbackTrackId WHERE pt.isRemoved=0";
            check(value(sql,joined)==3,"actual host join returns all three memberships");
            check(sql.rows("PRAGMA foreign_key_check").isEmpty(),"all actual schema foreign keys satisfied");
            var ids=Class.forName("com.xuncorp.voxzen.sync.\u0785");
            check(PlaylistLinks.stable("urn:com.xuncorp:sync:legacy-v34:entry:netease-1001").equals(ids.getMethod("\u037f",String.class).invoke(null,"netease-1001")),"track identity equals actual host migration");
            check(PlaylistLinks.stable("urn:com.xuncorp:sync:legacy-v34:playlist:netease-pl-1").equals(ids.getMethod("\u0528",String.class).invoke(null,"netease-pl-1")),"playlist identity equals actual host migration");
            var pair=sql.rows("SELECT p.syncId,s.syncId FROM Playlist p,SyncTrack s WHERE p.id='netease-pl-1' AND s.id='netease-1001'").getFirst();
            check(sql.rows("SELECT syncId FROM PlaylistTrack WHERE playlistId='netease-pl-1' AND syncTrackId='netease-1001'").getFirst()[0].equals(ids.getMethod("\u037f",String.class,String.class).invoke(null,pair[0],pair[1])),"membership identity equals actual host migration");
            sql.exec("UPDATE Playlist SET createdTime=17,isUserEditedCover=1 WHERE id='netease-pl-1'");
            sql.exec("UPDATE Track SET isFavorite=1,playCount=19 WHERE id='netease-1001'");
            sql.exec("UPDATE SyncTrack SET isFavorite=1 WHERE id='netease-1001'");
            write(connection,sql,entries);
            check(value(sql,joined)==3,"repeat synchronization retains shared tracks across playlists");
            check(value(sql,"SELECT createdTime FROM Playlist WHERE id='netease-pl-1'")==17,"repeat synchronization keeps creation time");
            check(value(sql,"SELECT isUserEditedCover FROM Playlist WHERE id='netease-pl-1'")==1,"keeps user cover flag");
            check(value(sql,"SELECT playCount FROM Track WHERE id='netease-1001'")==19,"keeps host play count");
            check(value(sql,"SELECT isFavorite FROM SyncTrack WHERE id='netease-1001'")==1,"keeps sync favorite");
            incremental(sql,true,a);
            write(connection,sql,entries); // Restore the base fixture for the existing rollback checks.
            sql.exec("CREATE TRIGGER reject_link BEFORE INSERT ON PlaylistTrack WHEN NEW.syncTrackId='netease-1002' BEGIN SELECT RAISE(ABORT,'injected'); END");
            try {write(connection,sql,entries);throw new AssertionError("failure ignored");}catch(SQLException expected){checks++;}
            check(value(sql,joined)==3,"failed write rolls back reset and partial inserts");
            sql.exec("DROP TRIGGER reject_link");
            sql.exec("UPDATE Playlist SET mergeStateData=X'01' WHERE id='netease-pl-1'");
            try {write(connection,sql,entries);throw new AssertionError("merged playlist overwritten");}catch(IllegalStateException expected){checks++;}
            check(value(sql,joined)==3,"host merge state refuses writes without losing memberships");
            sql.exec("UPDATE Playlist SET mergeStateData=X'' WHERE id='netease-pl-1'");
            sql.exec("UPDATE SyncTrack SET bindingEvidenceData=X'01' WHERE id='netease-1002'");
            try {write(connection,sql,entries);throw new AssertionError("binding overwritten");}catch(IllegalStateException expected){checks++;}
            check(value(sql,joined)==3,"host binding refusal rolls back the entire write");
            check(sql.rows("PRAGMA foreign_key_check").isEmpty(),"failure paths keep database consistent");
        }
        System.out.println("PLAYLIST_SCHEMA_RESULT="+checks+" PASS");
    }
}
