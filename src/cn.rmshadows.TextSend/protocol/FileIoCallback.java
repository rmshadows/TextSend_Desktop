package protocol;

/** 文件收发进度（UI 线程外回调，调用方自己 invokeLater）。 */
public interface FileIoCallback {
    /**
     * @param seq 当前是第几个文件（从 1 计）；0 表示不显示
     * @param of  这一批一共几个；≤1 时 UI 不显示 x/y
     */
    void onProgress(boolean incoming, String name, long done, long total, int seq, int of);

    void onReceived(String name, String where);

    void onReceiveFailed(String name, String err);
}
