package com.example.netease.core;

/**
 * 「一条 SQL 跑在某个连接上」的最小抽象（0.7.0 新增）。
 *
 * <p>存在的唯一理由：曲库写入要能跑在<b>两种完全不同的连接</b>上——</p>
 * <ul>
 *   <li>{@code svc/NativeLibrary} 自带的 {@code sqlite-jdbc} 外部连接（离线/宿主没起来时的回退）；</li>
 *   <li>宿主<b>自己的 Room 连接</b>（0.7.0 主线：只有同连接写入才会点着 Room 的失效触发器，
 *       宿主 UI 才会免重启即时刷新，见 {@code host/HostSqlBridge}）。</li>
 * </ul>
 *
 * <p>实现类必须**同步、非挂起**：调用方可能在宿主交互线程上直接跑。</p>
 */
public interface Sql {

    /**
     * 执行一条写语句（INSERT/UPDATE/DELETE/DDL），{@code ?} 占位按位绑定。
     *
     * @param sql  语句
     * @param args 绑定值：{@code String}/{@code Integer}/{@code Long}/{@code Double}/{@code byte[]}/{@code null}
     */
    void exec(String sql, Object... args) throws Exception;
}
