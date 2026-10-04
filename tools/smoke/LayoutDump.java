package com.example.netease.ui;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;

/**
 * 诊断用（不属于插件产物、不进任何门禁）：把 {@code LoginDialog.buildPreview()} 的组件树
 * <b>按真实布局</b>打印出来 —— 类名、bounds、preferred、min、max。
 *
 * <p>用途：离线皮肤探针报「宽度不对 / 某个控件没画出来」时，一眼看出是<b>布局</b>没给够、
 * 还是<b>绘制</b>没画。跑法与 DialogSkinProbe 同（同包，无反射）。</p>
 */
public final class LayoutDump {

    private LayoutDump() {
    }

    public static void main(String[] args) {
        JPanel pane = LoginDialog.buildPreview();
        JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setContentPane(pane);
        frame.pack();                                  // 与探针同一条路径：先 pack，再 dump
        System.out.println("=== 布局树（pack 之后）===");
        dump(pane, 0);
        System.out.println("frame=" + frame.getSize() + "  pane=" + pane.getSize()
                + "  panePref=" + pane.getPreferredSize());
        frame.dispose();
    }

    private static void dump(Component c, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(name(c)).append("  bounds=").append(c.getBounds());
        if (c instanceof JComponent jc) {
            Dimension p = jc.getPreferredSize();
            Dimension mn = jc.getMinimumSize();
            Dimension mx = jc.getMaximumSize();
            sb.append("  pref=").append(p.width).append('x').append(p.height);
            sb.append("  min=").append(mn.width).append('x').append(mn.height);
            sb.append("  max=").append(mx.width).append('x').append(mx.height);
            if (c instanceof javax.swing.JLabel l) {
                String t = l.getText() == null ? "" : l.getText().replace('\n', ' ');
                sb.append("  text=").append(t.length() > 28 ? t.substring(0, 28) + "…" : t);
            }
            if (c instanceof javax.swing.AbstractButton b) {
                sb.append("  text=").append(b.getText()).append("  enabled=").append(b.isEnabled());
            }
            if (c instanceof javax.swing.text.JTextComponent t) {
                sb.append("  text=\"").append(t.getText()).append('"').append("  enabled=").append(t.isEnabled());
            }
            if (c instanceof Container ct && ct.getLayout() != null) {
                sb.append("  layout=").append(ct.getLayout().getClass().getSimpleName());
            }
        }
        System.out.println(sb);
        if (c instanceof Container ct) {
            for (Component child : ct.getComponents()) {
                dump(child, depth + 1);
            }
        }
    }

    private static String name(Component c) {
        String s = c.getClass().getName();
        int i = s.lastIndexOf('.');
        return i < 0 ? s : s.substring(i + 1);
    }
}
