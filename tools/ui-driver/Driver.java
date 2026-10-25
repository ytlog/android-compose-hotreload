package dev.compose.hotreload.uidriver;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.net.*;

/** Read-only accessibility snapshots; touches still use the ordinary adb input path. */
public final class Driver extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        try (ServerSocket server = new ServerSocket()) {
            getUiAutomation();
            server.bind(new InetSocketAddress("127.0.0.1", 19283));
            try (Socket client = server.accept();
                 BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
                 PrintWriter output = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), "UTF-8"), true)) {
                for (String command; (command = input.readLine()) != null;) {
                    if (command.equals("QUIT")) break;
                    if (!command.equals("DUMP")) throw new IOException("Unknown driver request");
                    if (android.os.Build.VERSION.SDK_INT >= 36) getUiAutomation().clearCache();
                    AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
                    StringBuilder xml = new StringBuilder("<hierarchy>");
                    if (root != null) { append(xml, root); root.recycle(); }
                    output.println(xml.append("</hierarchy>"));
                }
            }
            finish(0, new Bundle());
        } catch (Throwable failure) {
            Bundle result = new Bundle(); result.putString("driverFailure", failure.toString()); finish(1, result);
        }
    }
    private static String escape(CharSequence value) {
        return value == null ? "" : value.toString().replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;").replace("\n", "&#10;").replace("\r", "&#13;");
    }
    private static void append(StringBuilder xml, AccessibilityNodeInfo node) {
        if (android.os.Build.VERSION.SDK_INT < 36) node.refresh();
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        xml.append("<node text=\"").append(escape(node.getText()))
            .append("\" class=\"").append(escape(node.getClassName()))
            .append("\" package=\"").append(escape(node.getPackageName()))
            .append("\" bounds=\"[").append(bounds.left).append(',').append(bounds.top)
            .append("][").append(bounds.right).append(',').append(bounds.bottom).append("]\">");
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { if (child.isVisibleToUser()) append(xml, child); child.recycle(); }
        }
        xml.append("</node>");
    }
}
