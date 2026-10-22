import com.example.netease.host.HostSqlBridge;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercise the real host transaction adapter against an in-memory SQLite connection. */
public final class HostTransactionProbe {
    public static final class ConnectionAdapter {
        final Connection connection;
        ConnectionAdapter(Connection c) { connection=c; }
        public StatementAdapter prepare(String sql) throws Exception { return new StatementAdapter(connection.prepareStatement(sql)); }
        public boolean inTransaction() throws Exception { return !connection.getAutoCommit(); }
    }
    public static final class StatementAdapter {
        final PreparedStatement statement;
        ResultSet rows;
        boolean started;
        StatementAdapter(PreparedStatement s) { statement=s; }
        public void bindNull(int index) throws Exception { statement.setNull(index,Types.NULL); }
        public void bindLong(int index,long value) throws Exception { statement.setLong(index,value); }
        public void bindDouble(int index,double value) throws Exception { statement.setDouble(index,value); }
        public void bindText(int index,String value) throws Exception { statement.setString(index,value); }
        public void bindBlob(int index,byte[] value) throws Exception { statement.setBytes(index,value); }
        public boolean step() throws Exception {
            if (!started) { started=true; if (statement.execute()) { rows=statement.getResultSet(); } }
            return rows!=null && rows.next();
        }
        public int getColumnCount() throws Exception { return rows.getMetaData().getColumnCount(); }
        public String getColumnName(int index) throws Exception { return rows.getMetaData().getColumnName(index+1); }
        public String getText(int index) throws Exception { return rows.getString(index+1); }
        public long getLong(int index) throws Exception { return rows.getLong(index+1); }
        public boolean isNull(int index) throws Exception { return rows.getObject(index+1)==null; }
        public void close() throws Exception { statement.close(); }
    }
    static int passed;
    static void check(boolean value,String label) {
        if (!value) { throw new AssertionError(label); }
        passed++; System.out.println("PASS "+label);
    }
    static long count(Connection c) throws Exception {
        try (Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM probe")) { return r.getLong(1); }
    }
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Class<?> adapter=Class.forName("com.example.netease.host.HostSqlBridge$HostSql");
        Constructor<?> construct=adapter.getDeclaredConstructor(Object.class); construct.setAccessible(true);
        Method transaction=HostSqlBridge.class.getDeclaredMethod("writeInTransaction",adapter,Object.class,HostSqlBridge.HostTask.class);
        transaction.setAccessible(true);
        try (Connection connection=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement s=connection.createStatement()) { s.execute("CREATE TABLE probe(value TEXT)"); }
            ConnectionAdapter raw=new ConnectionAdapter(connection);
            Object sql=construct.newInstance(raw);
            AtomicInteger validityChecks=new AtomicInteger();
            var canceled=HostSqlBridge.guarded(() -> validityChecks.incrementAndGet()<=2, x->{x.exec("INSERT INTO probe VALUES(?)","cancelled");return 1;});
            try { transaction.invoke(null,sql,raw,canceled); throw new AssertionError("cancel did not throw"); }
            catch (InvocationTargetException expected) { check(expected.getCause()!=null,"cancel propagated"); }
            check(count(connection)==0,"host adapter rolls back cancelled standalone transaction");
            transaction.invoke(null,sql,raw,(HostSqlBridge.HostTask<Integer>)x->{x.exec("INSERT INTO probe VALUES(?)","committed");return 1;});
            check(count(connection)==1,"host adapter commits successful standalone transaction");
            connection.setAutoCommit(false);
            try (Statement s=connection.createStatement()) { s.execute("INSERT INTO probe VALUES('outer')"); }
            try {
                transaction.invoke(null,sql,raw,(HostSqlBridge.HostTask<Integer>)x->{x.exec("INSERT INTO probe VALUES('partial')");throw new IllegalStateException("injected");});
                throw new AssertionError("failure did not throw");
            } catch (InvocationTargetException expected) { check(expected.getCause()!=null,"nested failure propagated"); }
            check(count(connection)==2,"nested rollback preserves caller transaction without partial plugin writes");
            transaction.invoke(null,sql,raw,(HostSqlBridge.HostTask<Integer>)x->{x.exec("INSERT INTO probe VALUES('nested success')");return 1;});
            check(count(connection)==3,"nested success releases plugin savepoint");
            connection.rollback();
            check(count(connection)==1,"caller retains ownership of outer transaction");
        }
        System.out.println("HOST_TRANSACTION_RESULT="+passed+" PASS");
    }
}
