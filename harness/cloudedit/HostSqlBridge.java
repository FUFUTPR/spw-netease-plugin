package com.example.netease.host;
import com.example.netease.core.Sql;
import java.util.function.BooleanSupplier;
public final class HostSqlBridge {
    public interface HostTask<T> { T run(Sql sql)throws Exception; default void checkValid(){} }
    public static <T>HostTask<T> guarded(BooleanSupplier valid,HostTask<T> task){return sql->{if(!valid.getAsBoolean())throw new IllegalStateException("stale");return task.run(sql);};}
    public static <T>T onHostConnection(HostTask<T> task){try{return task.run(null);}catch(Exception e){throw new RuntimeException(e);}}
}
