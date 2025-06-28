package org.antlr.v4.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.antlr.v4.server.persistent.PersistenceLayer;
import org.antlr.v4.server.persistent.cloudstorage.CloudStoragePersistenceLayer;
import org.antlr.v4.server.unique.DummyUniqueKeyGenerator;
import org.antlr.v4.server.unique.UniqueKeyGenerator;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.LoggerFactory;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Optional;

import static org.antlr.v4.server.GrammarProcessor.interp;

public class ANTLRHttpServer {
    public static String IMAGES_DIR = "./antlr-images";
    public static String WORK_DIR = "./work";

    static {
        String workPath = System.getenv("WORK_DIR");
        if (workPath != null) {
            File f =  new File(workPath);
            if(f.exists() && f.isDirectory()) {
                WORK_DIR = f.getAbsolutePath();
            }
        }
        String imagesPath = System.getenv("IMAGES_DIR");
        if (workPath != null) {
            File f =  new File(workPath);
            if(f.exists() && f.isDirectory()) {
                IMAGES_DIR = f.getAbsolutePath();
            }
        }
    }

    public static class ParseServlet extends DefaultServlet {
        static final ch.qos.logback.classic.Logger LOGGER = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ANTLRHttpServer.class);

        @Override
        public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
            LOGGER.info("INITIATE REQUEST IP: "+request.getRemoteAddr()+
                    ", Content-Length: "+request.getContentLength());
            logMemoryInfo("BEFORE PROCESSING FROM IP: "+request.getRemoteAddr());
            JsonObject jsonResponse = new JsonObject();
            try {
                response.setContentType("text/plain;charset=utf-8");
                response.setContentType("text/html;");
                response.addHeader("Access-Control-Allow-Origin", "*");

                JsonObject jsonObj = JsonParser.parseReader(request.getReader()).getAsJsonObject();

                String grammar = jsonObj.get("grammar").getAsString();
                String lexGrammar = jsonObj.get("lexgrammar").getAsString(); // can be null
                String input = jsonObj.get("input").getAsString();
                String startRule = jsonObj.get("start").getAsString();

                StringBuilder logMsg = new StringBuilder();
                logMsg.append("GRAMMAR:\n");
                logMsg.append(grammar);
                logMsg.append("\nLEX GRAMMAR:\n");
                logMsg.append(lexGrammar);
                logMsg.append("\nINPUT ("+input.length()+" char):\n\"\"\"");
                logMsg.append(input);
                logMsg.append("\"\"\"\n");
                logMsg.append("STARTRULE: ");
                logMsg.append(startRule);
                logMsg.append('\n');
                LOGGER.info(logMsg.toString());

                if (grammar.isBlank() && lexGrammar.isBlank()) {
                    jsonResponse.addProperty("arg_error", "missing either combined grammar or lexer and " + "parser both");
                }
                else if (grammar.isBlank()) {
                    jsonResponse.addProperty("arg_error", "missing parser grammar");
                }
                else if (startRule.isBlank()) {
                    jsonResponse.addProperty("arg_error", "missing start rule");
                }
                else if (input.isEmpty()) {
                    jsonResponse.addProperty("arg_error", "missing input");
                }
                else {
                    try {
                        jsonResponse = interp(grammar, lexGrammar, input, startRule);
                    }
                    catch (ParseCancellationException pce) {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        pce.printStackTrace(pw);
                        jsonResponse.addProperty("exception", pce.getMessage());
                        jsonResponse.addProperty("exception_trace", sw.toString());
                        LOGGER.warn(pce.toString());
                    }
                }
            }
            catch (Exception e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                jsonResponse.addProperty("exception", e.getMessage());
                jsonResponse.addProperty("exception_trace", sw.toString());
                LOGGER.error("PARSER FAILED", e);
            }

            response.setStatus(HttpServletResponse.SC_OK);
            PrintWriter w = response.getWriter();
            w.write(new Gson().toJson(jsonResponse));
            w.flush();

            // Don't save SVG tree in log; usually too big
            JsonElement result = jsonResponse.get("result");
            if ( result!=null && ((JsonObject) result).has("svgtree") ) {
                ((JsonObject) result).remove("svgtree");
            }
            logMemoryInfo("AFTER PARSE FROM IP: "+request.getRemoteAddr());
            LOGGER.info("RESULT:\n" + jsonResponse);
        }
    }

    private static void logMemoryInfo(String prefix) {
        Runtime.getRuntime().gc();
        var fm = Runtime.getRuntime().freeMemory();
        var tm = Runtime.getRuntime().totalMemory();
        NumberFormat.getInstance().format(fm);
        ParseServlet.LOGGER.info(prefix + " memory: free=" + NumberFormat.getInstance().format(fm) + " bytes" +
                ", total=" + NumberFormat.getInstance().format(tm) + " bytes");
    }

    public static class ShareServlet extends DefaultServlet {
        static final ch.qos.logback.classic.Logger LOGGER = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ANTLRHttpServer.class);

        @Override
        public void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
            final JsonObject jsonResponse = new JsonObject();
            try {
                response.setContentType("text/plain;charset=utf-8");
                response.setContentType("text/html;");
                response.addHeader("Access-Control-Allow-Origin", "*");

                JsonObject jsonObj = JsonParser.parseReader(request.getReader()).getAsJsonObject();
                PersistenceLayer<String> persistenceLayer = new CloudStoragePersistenceLayer();
                UniqueKeyGenerator keyGen = new DummyUniqueKeyGenerator();
                Optional<String> uniqueKey = keyGen.generateKey();
                persistenceLayer.persist(new Gson().toJson(jsonResponse).getBytes(StandardCharsets.UTF_8), uniqueKey.orElseThrow());

                jsonResponse.addProperty("resource_id", uniqueKey.orElseThrow());
            }
            catch (Exception e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                jsonResponse.addProperty("exception_trace", sw.toString());
                jsonResponse.addProperty("exception", e.getMessage());

            }
            LOGGER.info("RESULT:\n" + jsonResponse);
            response.setStatus(HttpServletResponse.SC_OK);
            PrintWriter w = response.getWriter();
            w.write(new Gson().toJson(jsonResponse));
            w.flush();
        }
    }

    public static class GrammarFileServlet extends DefaultServlet {
        static final ch.qos.logback.classic.Logger LOGGER = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger(GrammarFileServlet.class);

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String requestPath = request.getPathInfo(); // e.g., /my-grammar-name/parser.g4
            LOGGER.info("Grammar file request: " + requestPath);
            response.addHeader("Access-Control-Allow-Origin", "*");

            if (requestPath == null || requestPath.equals("/")) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing grammar name or file");
                return;
            }

            String[] parts = requestPath.split("/", 4);
            if (parts.length < 3) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid path. Expected /<name>/<file>");
                return;
            }

            String grammarName = parts[1];   // e.g., my-grammar-name
            String requestedFile = parts[2]; // e.g., parser, lexer, example

            File grammarDir = new File(WORK_DIR, grammarName);
            File configFile = new File(grammarDir, "antlr-lab.json");

            if (!configFile.exists()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Missing antlr-lab.json for grammar: " + grammarName);
                return;
            }

            try (Reader reader = Files.newBufferedReader(configFile.toPath())) {
                JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();

                String actualFileName;

                switch (requestedFile) {
                    default:
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid path.");
                        return;
                    case "parser":
                        actualFileName = config.get("parser").getAsString();
                        break;
                    case "lexer":
                        actualFileName = config.get("lexer").getAsString();
                        break;
                    case "example":
                        if (parts.length < 4) {
                            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid path. Expected /<name>/example/<file>");
                            return;
                        }
                        String exampleFile = parts[3];
                        JsonArray examples = config.has("example") ? config.get("example").getAsJsonArray() : new JsonArray();
                        boolean allowed = false;
                        for (JsonElement e : examples) {
                            if (e.getAsString().equals(exampleFile)) {
                                allowed = true;
                                break;
                            }
                        }
                        if (!allowed) {
                            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Requested file is not in 'example' list");
                            return;
                        }
                        actualFileName = exampleFile;
                        break;
                }

                File actualFile = new File(grammarDir, actualFileName);
                if (!actualFile.exists()) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "File not found: " + actualFileName);
                    return;
                }

                // Set content type
                if (actualFileName.endsWith(".g4")) {
                    response.setContentType("text/plain");
                } else {
                    response.setContentType(Files.probeContentType(actualFile.toPath()));
                }

                response.setStatus(HttpServletResponse.SC_OK);
                Files.copy(actualFile.toPath(), response.getOutputStream());

            } catch (Exception e) {
                LOGGER.error("Error serving grammar file", e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                try (PrintWriter out = response.getWriter()) {
                    e.printStackTrace(out);
                }
            }
        }
    }


    public static class ListGrammarsServlet extends DefaultServlet {

        static final ch.qos.logback.classic.Logger LOGGER = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ListGrammarsServlet.class);

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("application/json;charset=utf-8");
            response.addHeader("Access-Control-Allow-Origin", "*");

            JsonArray grammars = new JsonArray();
            File[] dirs = new File(WORK_DIR).listFiles(File::isDirectory);
            if (dirs != null) {

                for (File dir : dirs) {
                    File configFile = new File(dir, "antlr-lab.json");
                    if (!configFile.exists()) {
                        LOGGER.warn("No antlr-lab.json in: " + dir.getAbsolutePath());
                        continue;
                    }

                    try (Reader reader = Files.newBufferedReader(configFile.toPath())) {
                        JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();

                        String name = config.get("name").getAsString();
                        String lexer = config.get("lexer").getAsString();
                        String parser = config.get("parser").getAsString();
                        String start = config.has("start") ? config.get("start").getAsString() : "prog";

                        JsonArray examples = config.has("example") ? config.get("example").getAsJsonArray() : new JsonArray();

                        JsonObject grammar = new JsonObject();
                        grammar.addProperty("name", name);
                        grammar.addProperty("path", dir.getName());
                        grammar.addProperty("lexer", "/grammar/" + dir.getName() + "/lexer");
                        grammar.addProperty("parser", "/grammar/" + dir.getName() + "/parser");
                        grammar.addProperty("start", start);
                        grammar.add("example", examples);

                        grammars.add(grammar);
                    } catch (Exception e) {
                        LOGGER.error("Failed to parse config in: " + dir.getAbsolutePath(), e);
                    }
                }
            }

            PrintWriter out = response.getWriter();
            out.write(new Gson().toJson(grammars));
            out.flush();
        }
    }


    public static void main(String[] args) throws Exception {
        new File(IMAGES_DIR).mkdirs();

        Files.createDirectories(Path.of("./log/antlrlab"));
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMaxThreads(5);
        threadPool.setName("server");

        Server server = new Server(threadPool);

        ServerConnector http = new ServerConnector(server);
        http.setPort(8000);

        server.addConnector(http);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new ParseServlet()), "/parse/*");
        context.addServlet(new ServletHolder(new ShareServlet()), "/share/*");
        context.addServlet(new ServletHolder(new ListGrammarsServlet()), "/grammars");
        context.addServlet(new ServletHolder(new GrammarFileServlet()), "/grammar/*");


        ServletHolder holderHome = new ServletHolder("static-home", DefaultServlet.class);
        holderHome.setInitParameter("resourceBase", "static");
        holderHome.setInitParameter("dirAllowed", "true");
        holderHome.setInitParameter("pathInfoOnly", "true");
        context.addServlet(holderHome, "/*");

        server.setHandler(context);

        server.start();
    }
}
