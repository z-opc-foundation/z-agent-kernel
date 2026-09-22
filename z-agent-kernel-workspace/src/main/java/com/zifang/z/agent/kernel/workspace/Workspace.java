package com.zifang.z.agent.kernel.workspace;

import java.io.InputStream;
import java.util.List;

/**
 * 工作区 SPI. agent 文件/终端访问隔离沙箱.
 *
 * <p>read/write/list/exists: 文件操作.
 * <p>exec: 命令执行 (受 sandbox 限制).
 * <p>scope: 工作区标识 (workspace id / agent run id).
 */
public interface Workspace {

    String getScope();

    byte[] read(String path);

    void write(String path, byte[] content);

    void append(String path, byte[] content);

    boolean exists(String path);

    List<String> list(String dir);

    void delete(String path);

    ExecResult exec(String command, List<String> args, int timeoutMs);

    interface ExecResult {
        int getExitCode();

        String getStdout();

        String getStderr();

        InputStream getStdoutStream();
    }
}