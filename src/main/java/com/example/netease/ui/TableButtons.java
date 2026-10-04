package com.example.netease.ui;

import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

/**
 * 表格「行内按钮列」小工具（0.2.6「就绪播放」）。
 *
 * <p>做法：把某一列渲染成按钮外观（{@link JButton} 直接当 {@link TableCellRenderer} 组件），
 * 单击该列的单元格时回调行号（模型行号）。</p>
 *
 * <p><b>为什么不走 CellEditor</b>：编辑器要双击进入编辑态、还要管编辑生命周期（列宽、焦点、
 * 停止编辑），在「每行一个操作按钮」的场景里手感与代码量都更差；这里按钮只是<span>视觉</span>，
 * 命中判定由表级 {@code MouseListener} 完成，单击即生效。</p>
 *
 * <p>前提：目标表<b>不允许列重排</b>（{@code setReorderingAllowed(false)}，本工程两个表都是），
 * 因此「视图列号 == 模型列号」。</p>
 */
final class TableButtons {

    private TableButtons() {
    }

    /**
     * 把 {@code column} 列渲染成按钮，并在单击该列时回调 {@code onRow.accept(模型行号)}。
     *
     * @param table   目标表
     * @param column  列号（视图列号 == 模型列号；要求列重排已关闭）
     * @param fallback 单元格值为空时显示的按钮文字（模型不给值时兜底）
     * @param onRow   行点击回调（在 EDT 上执行）
     */
    static void install(JTable table, int column, String fallback, IntConsumer onRow) {
        if (table == null || column < 0 || column >= table.getColumnModel().getColumnCount()) {
            return;
        }
        table.getColumnModel().getColumn(column).setCellRenderer(new ButtonRenderer(fallback));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int viewCol = table.columnAtPoint(e.getPoint());
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewCol != column || viewRow < 0) {
                    return;
                }
                final int modelRow = table.convertRowIndexToModel(viewRow);
                try {
                    onRow.accept(modelRow);
                } catch (Throwable ignored) {
                    // 行内动作内部自己兜异常，这里只保证不把异常抛回 Swing 事件循环
                }
            }
        });
    }

    /** 按钮外观的渲染器（无状态、可复用）。 */
    private static final class ButtonRenderer extends JButton implements TableCellRenderer {
        private static final long serialVersionUID = 1L;

        private final String fallback;

        ButtonRenderer(String fallback) {
            this.fallback = fallback == null ? "" : fallback;
            setFocusable(false);
            setMargin(new Insets(1, 6, 1, 6));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            String text = value == null ? "" : String.valueOf(value);
            setText(text.isEmpty() ? fallback : text);
            if (table != null) {
                setFont(table.getFont());
            }
            return this;
        }
    }

    /** 给非按钮列用的默认渲染器（保留 JTable 原有观感）。 */
    static void keepDefault(JTable table, int column) {
        if (table == null || column < 0 || column >= table.getColumnModel().getColumnCount()) {
            return;
        }
        table.getColumnModel().getColumn(column).setCellRenderer(new DefaultTableCellRenderer());
    }
}
