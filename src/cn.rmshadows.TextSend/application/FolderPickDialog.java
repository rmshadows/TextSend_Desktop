package application;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 打勾选多个文件夹，点名字进入下一级。 */
public final class FolderPickDialog extends JDialog {
    private final File storageRoot;
    private File current;
    private final Set<String> selected = new LinkedHashSet<>();
    private final List<Row> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JLabel pathLabel = new JLabel();
    private final JButton currentBtn = new JButton("选择当前文件夹");
    private final JButton doneBtn = new JButton("加入已勾选");
    private List<File> result = List.of();

    private FolderPickDialog(Window owner, File start) {
        super(owner, "选择文件夹", ModalityType.APPLICATION_MODAL);
        File home = new File(System.getProperty("user.home", "."));
        storageRoot = home.isDirectory() ? home : new File("/");
        current = start != null && start.isDirectory() ? start : storageRoot;
        JTable table = new JTable(model);
        table.setRowHeight(UserConfig.s(28));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.getTableHeader().setReorderingAllowed(false);
        TableColumn checkCol = table.getColumnModel().getColumn(0);
        checkCol.setMaxWidth(UserConfig.s(40));
        checkCol.setMinWidth(UserConfig.s(36));
        table.getColumnModel().getColumn(2).setPreferredWidth(UserConfig.s(140));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int view = table.rowAtPoint(e.getPoint());
                if (view < 0) {
                    return;
                }
                int col = table.columnAtPoint(e.getPoint());
                Row row = rows.get(table.convertRowIndexToModel(view));
                if (col == 0 && row.file.isDirectory() && !row.up && !isRoot(row.file)) {
                    return;
                }
                if (row.up) {
                    goUp();
                    return;
                }
                if (row.file.isDirectory() && e.getClickCount() >= 1 && col != 0) {
                    current = row.file;
                    reload();
                }
            }
        });
        pathLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, UserConfig.s(4), 0));
        JLabel hint = new JLabel("左边打勾可一次选几个。点名字进入下一级。隐藏目录（如 .logger）也会列出。");
        JPanel north = new JPanel(new BorderLayout(0, UserConfig.s(4)));
        north.setBorder(BorderFactory.createEmptyBorder(UserConfig.s(10), UserConfig.s(12), UserConfig.s(6), UserConfig.s(12)));
        north.add(pathLabel, BorderLayout.NORTH);
        north.add(hint, BorderLayout.SOUTH);

        currentBtn.addActionListener(e -> {
            if (isRoot(current)) {
                return;
            }
            result = List.of(current);
            dispose();
        });
        doneBtn.addActionListener(e -> {
            if (selected.isEmpty()) {
                return;
            }
            List<File> out = new ArrayList<>();
            for (String p : selected) {
                File f = new File(p);
                if (f.isDirectory()) {
                    out.add(f);
                }
            }
            result = out;
            dispose();
        });
        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> {
            result = List.of();
            dispose();
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, UserConfig.s(8), UserConfig.s(8)));
        south.add(currentBtn);
        south.add(doneBtn);
        south.add(cancel);

        add(north, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(UserConfig.s(720), UserConfig.s(520)));
        pack();
        setLocationRelativeTo(owner);
        reload();
    }

    /** 取消则空列表。 */
    public static List<File> show(Window owner, File start) {
        FolderPickDialog d = new FolderPickDialog(owner, start);
        d.setVisible(true);
        return d.result;
    }

    static boolean skipJunk(String name) {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(".ds_store") || lower.equals("thumbs.db") || lower.equals("desktop.ini")) {
            return true;
        }
        return lower.startsWith(".textsend.")
                || lower.endsWith(".textsend.part")
                || lower.endsWith(".textsend.json")
                || lower.endsWith(".textsend.receiving");
    }

    private boolean isRoot(File dir) {
        if (dir == null) {
            return true;
        }
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File r : roots) {
                if (r.equals(dir)) {
                    return true;
                }
            }
        }
        return dir.getParentFile() == null;
    }

    private void goUp() {
        if (isRoot(current)) {
            return;
        }
        File parent = current.getParentFile();
        current = parent != null ? parent : storageRoot;
        reload();
    }

    private List<File> listKids(File dir) {
        LinkedHashMap<String, File> out = new LinkedHashMap<>();
        File[] raw = dir.listFiles();
        if (raw != null) {
            for (File f : raw) {
                if (!skipJunk(f.getName())) {
                    out.put(f.getName(), f);
                }
            }
        }
        if (isHome(dir)) {
            for (String hint : new String[]{"Downloads", "Download", "Documents", "Desktop", "nubialog", "nubia_log"}) {
                if (out.containsKey(hint)) {
                    continue;
                }
                File f = new File(dir, hint);
                if (f.isDirectory()) {
                    out.put(hint, f);
                }
            }
        }
        for (String hint : new String[]{".logger", ".log", ".logs", ".logcat", ".nubia", ".nubia_log", ".nubialog"}) {
            if (out.containsKey(hint) || skipJunk(hint)) {
                continue;
            }
            File f = new File(dir, hint);
            if (f.exists()) {
                out.put(hint, f);
            }
        }
        List<File> kids = new ArrayList<>(out.values());
        kids.sort(Comparator.comparing((File f) -> !f.isDirectory()).thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));
        return kids;
    }

    private boolean isHome(File dir) {
        return dir != null && dir.equals(storageRoot);
    }

    private void reload() {
        pathLabel.setText(current.getAbsolutePath());
        rows.clear();
        if (!isRoot(current)) {
            File parent = current.getParentFile();
            rows.add(new Row(parent != null ? parent : storageRoot, true));
        }
        for (File f : listKids(current)) {
            rows.add(new Row(f, false));
        }
        model.fireTableDataChanged();
        currentBtn.setEnabled(!isRoot(current));
        doneBtn.setEnabled(!selected.isEmpty());
        doneBtn.setText(selected.isEmpty() ? "加入已勾选" : "加入已勾选（" + selected.size() + "）");
    }

    private record Row(File file, boolean up) {
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "";
                case 1 -> "名称";
                default -> "类型";
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            if (columnIndex != 0) {
                return false;
            }
            Row row = rows.get(rowIndex);
            return !row.up && row.file.isDirectory() && !isRoot(row.file);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Row row = rows.get(rowIndex);
            if (row.up) {
                return switch (columnIndex) {
                    case 0 -> Boolean.FALSE;
                    case 1 -> "返回上级";
                    default -> "";
                };
            }
            File f = row.file;
            if (columnIndex == 0) {
                return selected.contains(f.getAbsolutePath());
            }
            if (columnIndex == 1) {
                return f.getName();
            }
            if (f.isDirectory()) {
                return "文件夹 · " + listKids(f).size() + " 项";
            }
            return fmtBytes(f.length());
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != 0 || !(aValue instanceof Boolean on)) {
                return;
            }
            Row row = rows.get(rowIndex);
            if (!row.file.isDirectory() || isRoot(row.file)) {
                return;
            }
            if (on) {
                selected.add(row.file.getAbsolutePath());
            } else {
                selected.remove(row.file.getAbsolutePath());
            }
            doneBtn.setEnabled(!selected.isEmpty());
            doneBtn.setText(selected.isEmpty() ? "加入已勾选" : "加入已勾选（" + selected.size() + "）");
        }
    }

    private static String fmtBytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        if (n < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
        }
        if (n < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", n / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.1f GB", n / (1024.0 * 1024 * 1024));
    }
}
