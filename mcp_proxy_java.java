import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * MCP Proxy Server - 將多個 MCP Server 組合成單一服務
 */
public class MCPProxyServer {
    
    private final Map<String, ServerConfig> servers = new ConcurrentHashMap<>();
    private final Map<String, Process> serverProcesses = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    static class ServerConfig {
        String name;
        String command;
        String[] args;
        String toolPrefix; // 工具名稱前綴,用於區分不同 server 的工具
        
        public ServerConfig(String name, String command, String[] args, String toolPrefix) {
            this.name = name;
            this.command = command;
            this.args = args;
            this.toolPrefix = toolPrefix;
        }
    }
    
    public MCPProxyServer() {
        // 註冊後端 MCP Servers
        registerServer(new ServerConfig(
            "weather-server",
            "node",
            new String[]{"path/to/weather-server.js"},
            "weather"
        ));
        
        registerServer(new ServerConfig(
            "database-server",
            "python",
            new String[]{"path/to/db-server.py"},
            "db"
        ));
    }
    
    public void registerServer(ServerConfig config) {
        servers.put(config.name, config);
    }
    
    /**
     * 啟動所有後端 Server
     */
    public void startAllServers() throws IOException {
        for (Map.Entry<String, ServerConfig> entry : servers.entrySet()) {
            startServer(entry.getKey());
        }
    }
    
    private void startServer(String serverName) throws IOException {
        ServerConfig config = servers.get(serverName);
        if (config == null) return;
        
        ProcessBuilder pb = new ProcessBuilder();
        List<String> command = new ArrayList<>();
        command.add(config.command);
        command.addAll(Arrays.asList(config.args));
        pb.command(command);
        
        Process process = pb.start();
        serverProcesses.put(serverName, process);
        System.err.println("Started server: " + serverName);
    }
    
    /**
     * 主要的請求處理循環
     */
    public void run() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        
        while ((line = reader.readLine()) != null) {
            try {
                JsonNode request = objectMapper.readTree(line);
                JsonNode response = handleRequest(request);
                
                if (response != null) {
                    System.out.println(objectMapper.writeValueAsString(response));
                    System.out.flush();
                }
            } catch (Exception e) {
                sendError(e.getMessage());
            }
        }
    }
    
    /**
     * 處理 MCP 請求
     */
    private JsonNode handleRequest(JsonNode request) throws Exception {
        String method = request.get("method").asText();
        
        switch (method) {
            case "initialize":
                return handleInitialize(request);
            case "tools/list":
                return handleListTools(request);
            case "tools/call":
                return handleCallTool(request);
            default:
                throw new Exception("Unknown method: " + method);
        }
    }
    
    /**
     * 處理初始化請求
     */
    private JsonNode handleInitialize(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        
        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", "mcp-proxy-server");
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);
        
        ObjectNode capabilities = objectMapper.createObjectNode();
        ObjectNode tools = objectMapper.createObjectNode();
        capabilities.set("tools", tools);
        result.set("capabilities", capabilities);
        
        response.set("result", result);
        return response;
    }
    
    /**
     * 列出所有後端 Server 的工具
     */
    private JsonNode handleListTools(JsonNode request) throws Exception {
        ArrayNode allTools = objectMapper.createArrayNode();
        
        // 從每個後端 server 獲取工具列表
        for (Map.Entry<String, ServerConfig> entry : servers.entrySet()) {
            String serverName = entry.getKey();
            ServerConfig config = entry.getValue();
            
            try {
                ArrayNode serverTools = fetchToolsFromServer(serverName);
                
                // 為每個工具添加前綴,避免名稱衝突
                for (JsonNode tool : serverTools) {
                    ObjectNode modifiedTool = (ObjectNode) tool;
                    String originalName = tool.get("name").asText();
                    modifiedTool.put("name", config.toolPrefix + "_" + originalName);
                    modifiedTool.put("description", 
                        "[" + serverName + "] " + tool.get("description").asText());
                    allTools.add(modifiedTool);
                }
            } catch (Exception e) {
                System.err.println("Error fetching tools from " + serverName + ": " + e.getMessage());
            }
        }
        
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", allTools);
        response.set("result", result);
        
        return response;
    }
    
    /**
     * 從後端 Server 獲取工具列表
     */
    private ArrayNode fetchToolsFromServer(String serverName) throws Exception {
        // 構建 tools/list 請求
        ObjectNode listRequest = objectMapper.createObjectNode();
        listRequest.put("jsonrpc", "2.0");
        listRequest.put("id", UUID.randomUUID().toString());
        listRequest.put("method", "tools/list");
        
        // 發送到後端 server 並獲取響應
        JsonNode response = sendToBackendServer(serverName, listRequest);
        
        if (response.has("result") && response.get("result").has("tools")) {
            return (ArrayNode) response.get("result").get("tools");
        }
        
        return objectMapper.createArrayNode();
    }
    
    /**
     * 調用工具 - 路由到對應的後端 Server
     */
    private JsonNode handleCallTool(JsonNode request) throws Exception {
        JsonNode params = request.get("params");
        String toolName = params.get("name").asText();
        
        // 根據工具名稱前綴找到對應的 server
        String targetServer = findServerByToolName(toolName);
        if (targetServer == null) {
            throw new Exception("Unknown tool: " + toolName);
        }
        
        // 移除前綴,獲取原始工具名稱
        ServerConfig config = servers.get(targetServer);
        String originalToolName = toolName.substring(config.toolPrefix.length() + 1);
        
        // 構建轉發請求
        ObjectNode forwardRequest = objectMapper.createObjectNode();
        forwardRequest.put("jsonrpc", "2.0");
        forwardRequest.set("id", request.get("id"));
        forwardRequest.put("method", "tools/call");
        
        ObjectNode forwardParams = objectMapper.createObjectNode();
        forwardParams.put("name", originalToolName);
        forwardParams.set("arguments", params.get("arguments"));
        forwardRequest.set("params", forwardParams);
        
        // 轉發到後端 server
        return sendToBackendServer(targetServer, forwardRequest);
    }
    
    /**
     * 根據工具名稱找到對應的 Server
     */
    private String findServerByToolName(String toolName) {
        for (Map.Entry<String, ServerConfig> entry : servers.entrySet()) {
            if (toolName.startsWith(entry.getValue().toolPrefix + "_")) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * 發送請求到後端 Server
     */
    private JsonNode sendToBackendServer(String serverName, JsonNode request) throws Exception {
        Process process = serverProcesses.get(serverName);
        if (process == null || !process.isAlive()) {
            throw new Exception("Server " + serverName + " is not running");
        }
        
        // 寫入請求
        OutputStream out = process.getOutputStream();
        out.write((objectMapper.writeValueAsString(request) + "\n").getBytes());
        out.flush();
        
        // 讀取響應
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream())
        );
        String responseLine = reader.readLine();
        
        return objectMapper.readTree(responseLine);
    }
    
    /**
     * 發送錯誤響應
     */
    private void sendError(String message) {
        try {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("jsonrpc", "2.0");
            error.put("id", (String) null);
            
            ObjectNode errorObj = objectMapper.createObjectNode();
            errorObj.put("code", -32603);
            errorObj.put("message", message);
            error.set("error", errorObj);
            
            System.out.println(objectMapper.writeValueAsString(error));
            System.out.flush();
        } catch (Exception e) {
            System.err.println("Error sending error: " + e.getMessage());
        }
    }
    
    /**
     * 關閉所有後端 Server
     */
    public void shutdown() {
        for (Process process : serverProcesses.values()) {
            process.destroy();
        }
    }
    
    public static void main(String[] args) {
        MCPProxyServer proxy = new MCPProxyServer();
        
        // 添加 shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Shutting down proxy server...");
            proxy.shutdown();
        }));
        
        try {
            proxy.startAllServers();
            proxy.run();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
