package com.ossfilebrowse.plus.controller;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import com.ossfilebrowse.plus.utils.Base64Utils;
import com.ossfilebrowse.plus.utils.ConfigLoader;
import com.ossfilebrowse.plus.utils.HttpUtils;
import com.ossfilebrowse.plus.utils.XMLParser;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.awt.Desktop;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainController {


    @FXML
    private TextField tf_kkfileview_url, tf_oss_url, tf_proxy_host, tf_proxy_port, tf_search, tf_image_extensions, tf_allow_extensions;
    @FXML
    private ComboBox<String> cb_proxy_type;
    @FXML
    private TextArea ta_custom_headers;
    @FXML
    private Label lb_proxy_status;
    @FXML
    private WebView webView;
    @FXML
    private TreeView<String> treeView;
    @FXML
    private TreeItem<String> root;

    private List<String> previewFileUrls = new ArrayList<>();
    private List<String> allFilePaths = new ArrayList<>();
    private final Map<String, String> pathToUrl = new LinkedHashMap<>();
    private int currentIndex = 0;
    private boolean loadingInProgress = false;

    private String allowExtensions = ConfigLoader.getProperty("allow.extensions");
    private String imageExtensions = ConfigLoader.getProperty("image.extensions");
    private String kkFileView_URL = ConfigLoader.getProperty("kkFileView_URL");
    private final String proxyType = ConfigLoader.getProperty("proxy.type");
    private final String proxyHost = ConfigLoader.getProperty("proxy.host");
    private final String proxyPort = ConfigLoader.getProperty("proxy.port");
    private final String defaultRequestHeaders = ConfigLoader.getProperty("request.headers");
    private static final String PREVIEW_API = "/onlinePreview?url=";
    private Set<String> allowExtensionSet = new LinkedHashSet<>();
    private Set<String> imageExtensionSet = new LinkedHashSet<>();

    // 初始化
    @FXML
    private void initialize() {
        if (!kkFileView_URL.endsWith("/")) {
            kkFileView_URL = kkFileView_URL + "/";
        }
        tf_kkfileview_url.setText(kkFileView_URL);
        this.allowExtensionSet = parseAllowExtensions(allowExtensions);
        this.imageExtensionSet = parseAllowExtensions(imageExtensions);
        if (tf_image_extensions != null) {
            tf_image_extensions.setText(imageExtensions == null ? "" : imageExtensions);
        }
        if (tf_allow_extensions != null) {
            tf_allow_extensions.setText(allowExtensions == null ? "" : allowExtensions);
        }
        cb_proxy_type.getItems().setAll("无代理", "SOCKS5", "HTTP");
        initProxyFromConfig();
        ta_custom_headers.setText(formatHeadersForTextarea(defaultRequestHeaders));
        refreshProxyStatusFromSystem();
        if (tf_search != null) {
            tf_search.textProperty().addListener((obs, oldVal, newVal) -> applyTreeFilter(newVal));
        }

        treeView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            handleNodeSelected(newValue);
        });
        initTreeViewContextMenu();
    }

    @FXML
    protected void Loading() {
        if (loadingInProgress) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "正在加载中，请稍候");
            return;
        }

        String rawOssUrl = tf_oss_url.getText() == null ? "" : tf_oss_url.getText().trim();
        if (rawOssUrl.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "请输入存储桶地址");
            return;
        }
        if (!applyProxyInternal(false)) {
            return;
        }
        String normalizedOssUrl = normalizeUrlWithTrailingSlash(rawOssUrl);
        final Map<String, String> customHeaders = parseCustomHeaders(ta_custom_headers.getText());

        loadingInProgress = true;
        Task<String> loadTask = new Task<String>() {
            @Override
            protected String call() throws Exception {
                return HttpUtils.httpGet(normalizedOssUrl, customHeaders);
            }
        };

        loadTask.setOnSucceeded(event -> {
            loadingInProgress = false;
            String responseData = loadTask.getValue();
            if (XMLParser.isXMLData(responseData)) {
                if (XMLParser.containsListBucketResult(responseData)) {
                    String[] keys = XMLParser.extractKeys(responseData);
                    if (keys.length > 0) {
                        constructFileUrls(normalizedOssUrl, keys);
                        currentIndex = 0;
                        loadByIndex(currentIndex);
                    } else {
                        showAlert(Alert.AlertType.INFORMATION, "提示", null, "存储桶为空");
                    }
                } else if (XMLParser.containsAccessDenied(responseData)) {
                    showAlert(Alert.AlertType.INFORMATION, "提示", null, "存储桶禁止访问");
                } else {
                    showAlert(Alert.AlertType.INFORMATION, "提示", null, "不是存储桶");
                }
            } else {
                showAlert(Alert.AlertType.INFORMATION, "提示", null, "存储桶遍历漏洞不存在");
            }
        });

        loadTask.setOnFailed(event -> {
            loadingInProgress = false;
            Throwable error = loadTask.getException();
            String message = error == null ? "加载失败" : error.getMessage();
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "加载失败：" + message);
        });

        Thread worker = new Thread(loadTask, "oss-load-task");
        worker.setDaemon(true);
        worker.start();
    }

    @FXML
    protected void ApplyProxy() {
        applyProxyInternal(true);
    }

    @FXML
    protected void ExportLeftResourcesTxt() {
        if (root == null || root.getChildren().isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "左侧没有可导出的资源，请先加载");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出左侧资源到TXT");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        chooser.setInitialFileName("oss_resources_" + time + ".txt");

        File target = chooser.showSaveDialog(treeView.getScene().getWindow());
        if (target == null) {
            return;
        }

        int count = 0;
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), "UTF-8"))) {
            for (TreeItem<String> item : root.getChildren()) {
                String path = item.getValue();
                String url = pathToUrl.get(path);
                if (url == null || url.trim().isEmpty()) {
                    // 兜底拼接完整URL
                    String base = normalizeUrlWithTrailingSlash(tf_oss_url.getText() == null ? "" : tf_oss_url.getText().trim());
                    if (!base.isEmpty()) {
                        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
                        url = base + normalizedPath;
                    } else {
                        url = path;
                    }
                }
                // 仅导出完整URL
                writer.write(url);
                writer.newLine();
                count++;
            }
            writer.flush();
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "导出成功，共 " + count + " 条\n" + target.getAbsolutePath());
        } catch (Exception e) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "导出失败：" + e.getMessage());
        }
    }

    @FXML
    protected void ApplyPreviewExtensions() {
        String rawImage = tf_image_extensions == null ? "" : tf_image_extensions.getText();
        String rawAllow = tf_allow_extensions == null ? "" : tf_allow_extensions.getText();

        this.imageExtensionSet = parseAllowExtensions(rawImage);
        this.allowExtensionSet = parseAllowExtensions(rawAllow);
        this.imageExtensions = rawImage == null ? "" : rawImage.trim();
        this.allowExtensions = rawAllow == null ? "" : rawAllow.trim();

        showAlert(Alert.AlertType.INFORMATION, "提示", null, "预览后缀已应用。修改 allow.extensions 后请点击“加载”重新拉取资源列表。");
    }

    @FXML
    protected void SearchResources() {
        applyTreeFilter(tf_search == null ? "" : tf_search.getText());
    }

    @FXML
    protected void ClearSearch() {
        if (tf_search != null) {
            tf_search.clear();
        } else {
            applyTreeFilter("");
        }
    }

    @FXML
    protected void PreviousFile() {
        if (!previewFileUrls.isEmpty() && currentIndex > 0) {
            currentIndex--;
            loadByIndex(currentIndex);
        } else {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "已经是第一个文件");
        }
    }

    @FXML
    protected void NextFile() {
        if (!previewFileUrls.isEmpty() && currentIndex < previewFileUrls.size() - 1) {
            currentIndex++;
            loadByIndex(currentIndex);
        } else {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "已加载所有文件");
        }
    }

    private void loadByIndex(int index) {
        if (previewFileUrls.isEmpty() || index < 0 || index >= previewFileUrls.size()) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "已加载所有文件");
            return;
        }
        String targetUrl = previewFileUrls.get(index);
        renderPreview(targetUrl);
    }

    // 文件列表
    private void constructFileUrls(String oss_url, String[] keys) {
        previewFileUrls.clear();
        allFilePaths.clear();
        pathToUrl.clear();
        root = new TreeItem<>("Files"); // 创建根节点

        for (int i = 0; i < keys.length; i++) {
            String fileUrl = oss_url + keys[i];

            try {
                URL url = new URL(fileUrl);
                String path = url.getPath(); // 获取 URL 的路径部分
                String extension = getFileExtension(path); // 获取文件扩展名

                if (allowExtensionSet.isEmpty() || allowExtensionSet.contains(extension)) {
                    previewFileUrls.add(fileUrl);
                    pathToUrl.put(path, fileUrl);
                    allFilePaths.add(path);
                }
            } catch (Exception e) {
                e.printStackTrace();
                showAlert(Alert.AlertType.INFORMATION, "提示", null, "无法解析文件列表里的url：" + fileUrl);
            }
        }

        applyTreeFilter(tf_search == null ? "" : tf_search.getText());
        treeView.setRoot(root);
        treeView.setShowRoot(false);
    }

    // 处理节点被选中的方法
    private void handleNodeSelected(TreeItem<String> selectedItem) {
        if (selectedItem != null) {
            String selectedPath = selectedItem.getValue();
            String url = pathToUrl.get(selectedPath);
            if (url == null) {
                url = remove(tf_oss_url.getText()) + selectedPath;
            }
            int selectedIndex = previewFileUrls.indexOf(url);
            if (selectedIndex >= 0) {
                currentIndex = selectedIndex;
            }
            renderPreview(url);
        }
    }

    private void renderPreview(String rawUrl) {
        webView.setPrefSize(800, 800);
        String ext = getFileExtension(rawUrl);
        if (".webp".equals(ext)) {
            webView.getEngine().loadContent(buildWebpFallbackHtml(rawUrl), "text/html");
            return;
        }
        webView.getEngine().load(buildPreviewTarget(rawUrl));
    }

    // 获取oss资源的后缀
    private String getFileExtension(String url) {
        int lastDotIndex = url.lastIndexOf('.');
        if (lastDotIndex >= 0) {
            return url.substring(lastDotIndex).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    public static void showAlert(Alert.AlertType type, String title, String headerText, String contentText) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(headerText);
        alert.setContentText(contentText);
        alert.showAndWait();
    }

    private Set<String> parseAllowExtensions(String raw) {
        if (raw == null) {
            return new LinkedHashSet<>();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<String> set = new LinkedHashSet<>();
        Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith(".") ? s.toLowerCase(Locale.ROOT) : ("." + s.toLowerCase(Locale.ROOT)))
                .forEach(set::add);
        return set;
    }

    private boolean applyProxyInternal(boolean notify) {
        String type = cb_proxy_type.getSelectionModel().getSelectedItem();
        String host = tf_proxy_host.getText() == null ? "" : tf_proxy_host.getText().trim();
        String port = tf_proxy_port.getText() == null ? "" : tf_proxy_port.getText().trim();

        clearAllProxy();

        if (type == null || "无代理".equals(type)) {
            refreshProxyStatusFromSystem();
            if (notify) {
                showAlert(Alert.AlertType.INFORMATION, "提示", null, "已清除全局代理");
            }
            return true;
        }

        if (host.isEmpty() || port.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "代理主机和端口不能为空");
            return false;
        }

        int portValue;
        try {
            portValue = Integer.parseInt(port);
            if (portValue < 1 || portValue > 65535) {
                showAlert(Alert.AlertType.INFORMATION, "提示", null, "代理端口范围应为 1-65535");
                return false;
            }
        } catch (NumberFormatException e) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "代理端口必须是数字");
            return false;
        }

        if ("SOCKS5".equals(type)) {
            System.setProperty("socksProxyHost", host);
            System.setProperty("socksProxyPort", String.valueOf(portValue));
        } else if ("HTTP".equals(type)) {
            System.setProperty("http.proxyHost", host);
            System.setProperty("http.proxyPort", String.valueOf(portValue));
            System.setProperty("https.proxyHost", host);
            System.setProperty("https.proxyPort", String.valueOf(portValue));
        }

        refreshProxyStatusFromSystem();
        if (notify) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, type + " 代理已生效：" + host + ":" + portValue);
        }
        return true;
    }

    private void clearAllProxy() {
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
    }

    private void refreshProxyStatusFromSystem() {
        String socksHost = System.getProperty("socksProxyHost", "");
        String socksPort = System.getProperty("socksProxyPort", "");
        String httpHost = System.getProperty("http.proxyHost", "");
        String httpPort = System.getProperty("http.proxyPort", "");

        boolean socksActive = socksHost != null && !socksHost.trim().isEmpty() && socksPort != null && !socksPort.trim().isEmpty();
        boolean httpActive = httpHost != null && !httpHost.trim().isEmpty() && httpPort != null && !httpPort.trim().isEmpty();

        updateProxyStatusLabel(socksActive, socksHost, socksPort, httpActive, httpHost, httpPort);
    }

    private void updateProxyStatusLabel(boolean socksActive, String socksHost, String socksPort, boolean httpActive, String httpHost, String httpPort) {
        if (lb_proxy_status == null) {
            return;
        }
        if (socksActive || httpActive) {
            StringBuilder sb = new StringBuilder("代理状态：");
            if (socksActive) {
                sb.append("SOCKS5 ").append(socksHost).append(":").append(socksPort);
            }
            if (httpActive) {
                if (socksActive) {
                    sb.append(" | ");
                }
                sb.append("HTTP ").append(httpHost).append(":").append(httpPort);
            }
            lb_proxy_status.setText(sb.toString());
            lb_proxy_status.setStyle("-fx-text-fill: #16a34a;");
        } else {
            lb_proxy_status.setText("代理状态：未生效");
            lb_proxy_status.setStyle("-fx-text-fill: #d70b0b;");
        }
    }

    private void initProxyFromConfig() {
        String type = proxyType == null ? "" : proxyType.trim().toUpperCase(Locale.ROOT);
        String host = proxyHost == null ? "" : proxyHost.trim();
        String port = proxyPort == null ? "" : proxyPort.trim();

        if (!"SOCKS5".equals(type) && !"HTTP".equals(type)) {
            cb_proxy_type.getSelectionModel().select("无代理");
        } else {
            cb_proxy_type.getSelectionModel().select(type);
            tf_proxy_host.setText(host);
            tf_proxy_port.setText(port);
        }
    }

    private String buildPreviewUrl(String rawUrl) {
        String base = tf_kkfileview_url.getText();
        if (base == null || base.trim().isEmpty()) {
            base = kkFileView_URL;
        } else if (!base.endsWith("/")) {
            base = base + "/";
        }
        try {
            // 对齐 kkFileView 官方示例：url=encodeURIComponent(base64Encode(rawUrl))
            String b64 = Base64Utils.encode(rawUrl);
            String encoded = URLEncoder.encode(b64, "UTF-8");
            return base + PREVIEW_API + encoded;
        } catch (Exception e) {
            return base + PREVIEW_API + Base64Utils.encode(rawUrl);
        }
    }

    private String buildPreviewTarget(String rawUrl) {
        String ext = getFileExtension(rawUrl);
        if (isImageExtension(ext)) {
            // 图片直接预览，避免经过 kkFileView 造成预览失败
            return rawUrl;
        }
        return buildPreviewUrl(rawUrl);
    }

    private boolean isImageExtension(String ext) {
        if (ext == null) {
            return false;
        }
        if (imageExtensionSet == null || imageExtensionSet.isEmpty()) {
            return false;
        }
        String e = ext.toLowerCase(Locale.ROOT);
        return imageExtensionSet.contains(e);
    }

    private String buildWebpFallbackHtml(String rawUrl) {
        String safeUrl = escapeHtml(rawUrl);
        return "<html><body style=\"font-family: Arial, sans-serif; background:#f8fafc; color:#0f172a; padding:16px;\">"
                + "<h3 style=\"margin:0 0 12px 0;\">WEBP 预览兜底</h3>"
                + "<p style=\"margin:0 0 8px 0;\">当前 JavaFX WebView 可能不支持 WEBP，请使用浏览器访问原始链接。</p>"
                + "<p style=\"margin:0;\">原始链接：<a href=\"" + safeUrl + "\">" + safeUrl + "</a></p>"
                + "</body></html>";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    //删除url末尾的/
    private String remove(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String normalizeUrlWithTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url;
        }
        return url + "/";
    }

    private Map<String, String> parseCustomHeaders(String raw) {
        Map<String, String> headerMap = new LinkedHashMap<String, String>();
        if (raw == null) {
            return headerMap;
        }
        String content = raw.trim();
        if (content.isEmpty()) {
            return headerMap;
        }
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String lineText = line == null ? "" : line.trim();
            if (lineText.isEmpty()) {
                continue;
            }
            for (String item : splitHeaderItems(lineText)) {
                String part = item == null ? "" : item.trim();
                if (part.isEmpty()) {
                    continue;
                }
                int index = part.indexOf(':');
                if (index <= 0) {
                    continue;
                }
                String key = part.substring(0, index).trim();
                String value = part.substring(index + 1).trim();
                if (!key.isEmpty()) {
                    headerMap.put(key, value);
                }
            }
        }
        return headerMap;
    }

    private String formatHeadersForTextarea(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        return String.join("\n", splitHeaderItems(value));
    }

    private List<String> splitHeaderItems(String text) {
        List<String> items = new ArrayList<String>();
        if (text == null) {
            return items;
        }
        String content = text.trim();
        if (content.isEmpty()) {
            return items;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == ';' && isHeaderDelimiter(content, i + 1)) {
                String part = current.toString().trim();
                if (!part.isEmpty()) {
                    items.add(part);
                }
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            items.add(tail);
        }
        return items;
    }

    private boolean isHeaderDelimiter(String content, int nextIndex) {
        int i = nextIndex;
        while (i < content.length() && Character.isWhitespace(content.charAt(i))) {
            i++;
        }
        int keyStart = i;
        while (i < content.length()) {
            char ch = content.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_') {
                i++;
            } else {
                break;
            }
        }
        if (i == keyStart) {
            return false;
        }
        while (i < content.length() && Character.isWhitespace(content.charAt(i))) {
            i++;
        }
        return i < content.length() && content.charAt(i) == ':';
    }

    private void applyTreeFilter(String keyword) {
        if (root == null) {
            root = new TreeItem<>("Files");
        }
        root.getChildren().clear();
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        for (String path : allFilePaths) {
            if (query.isEmpty() || path.toLowerCase(Locale.ROOT).contains(query)) {
                root.getChildren().add(new TreeItem<String>(path));
            }
        }
        treeView.setRoot(root);
        treeView.setShowRoot(false);
    }

    private void initTreeViewContextMenu() {
        treeView.setCellFactory(tv -> {
            javafx.scene.control.TreeCell<String> cell = new javafx.scene.control.TreeCell<String>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : item);
                }
            };

            MenuItem copyUrlItem = new MenuItem("复制链接");
            copyUrlItem.setOnAction(event -> copyResourceUrl(cell.getTreeItem()));
            MenuItem copyPreviewUrlItem = new MenuItem("复制预览链接");
            copyPreviewUrlItem.setOnAction(event -> copyPreviewUrl(cell.getTreeItem()));
            MenuItem openInBrowserItem = new MenuItem("浏览器打开原始链接");
            openInBrowserItem.setOnAction(event -> openResourceInBrowser(cell.getTreeItem()));

            ContextMenu contextMenu = new ContextMenu(copyUrlItem, copyPreviewUrlItem, openInBrowserItem);
            cell.emptyProperty().addListener((obs, oldEmpty, newEmpty) -> {
                cell.setContextMenu(newEmpty ? null : contextMenu);
            });
            return cell;
        });
    }

    private void copyResourceUrl(TreeItem<String> item) {
        if (item == null || item.getValue() == null) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "未选中资源");
            return;
        }
        String path = item.getValue();
        String url = pathToUrl.get(path);
        if (url == null || url.trim().isEmpty()) {
            String base = normalizeUrlWithTrailingSlash(tf_oss_url.getText() == null ? "" : tf_oss_url.getText().trim());
            if (!base.isEmpty()) {
                String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
                url = base + normalizedPath;
            } else {
                url = path;
            }
        }

        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(url);
        clipboard.setContent(content);
        showAlert(Alert.AlertType.INFORMATION, "提示", null, "复制成功");
    }

    private void copyPreviewUrl(TreeItem<String> item) {
        if (item == null || item.getValue() == null) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "未选中资源");
            return;
        }
        String path = item.getValue();
        String rawUrl = pathToUrl.get(path);
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            String base = normalizeUrlWithTrailingSlash(tf_oss_url.getText() == null ? "" : tf_oss_url.getText().trim());
            if (!base.isEmpty()) {
                String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
                rawUrl = base + normalizedPath;
            } else {
                rawUrl = path;
            }
        }

        String previewUrl = buildPreviewTarget(rawUrl);
        Clipboard clipboard = Clipboard.getSystemClipboard();
        ClipboardContent content = new ClipboardContent();
        content.putString(previewUrl);
        clipboard.setContent(content);
        showAlert(Alert.AlertType.INFORMATION, "提示", null, "预览链接复制成功");
    }

    private void openResourceInBrowser(TreeItem<String> item) {
        if (item == null || item.getValue() == null) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "未选中资源");
            return;
        }
        String path = item.getValue();
        String rawUrl = pathToUrl.get(path);
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            String base = normalizeUrlWithTrailingSlash(tf_oss_url.getText() == null ? "" : tf_oss_url.getText().trim());
            if (!base.isEmpty()) {
                String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
                rawUrl = base + normalizedPath;
            } else {
                rawUrl = path;
            }
        }

        try {
            if (!Desktop.isDesktopSupported()) {
                showAlert(Alert.AlertType.INFORMATION, "提示", null, "当前环境不支持打开系统浏览器");
                return;
            }
            Desktop.getDesktop().browse(new URI(rawUrl));
        } catch (Exception e) {
            showAlert(Alert.AlertType.INFORMATION, "提示", null, "打开浏览器失败：" + e.getMessage());
        }
    }
}
