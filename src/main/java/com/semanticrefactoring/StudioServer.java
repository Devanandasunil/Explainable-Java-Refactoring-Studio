package com.semanticrefactoring;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

public class StudioServer {
  private static final int MAX_SOURCE_BYTES = 1_000_000;

  public static void main(String[] args) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8765), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/", x -> {
      if (!x.getRequestMethod().equalsIgnoreCase("GET")) { send(x,405,"text/plain","GET required"); return; }
      String p=x.getRequestURI().getPath(); if(p.equals("/"))p="/index.html";
      if (!List.of("/index.html", "/styles.css", "/app.js", "/refactor.js", "/ai-refactor.js").contains(p)) { send(x,404,"text/plain","Not found"); return; }
      try(InputStream in=StudioServer.class.getResourceAsStream("/web"+p)){
        if(in==null){send(x,404,"text/plain","Not found");return;}
        String type=p.endsWith(".css")?"text/css; charset=utf-8":p.endsWith(".js")?"application/javascript; charset=utf-8":"text/html; charset=utf-8";
        send(x,200,type,new String(in.readAllBytes(),StandardCharsets.UTF_8));
      } catch(Exception e){send(x,500,"text/plain",e.toString());}
    });
    server.createContext("/api/analyze", x -> {
      if(!x.getRequestMethod().equalsIgnoreCase("POST")){send(x,405,"application/json","{\"error\":\"POST required\"}");return;}
      try {
        String source=readSource(x);
        send(x,200,"application/json; charset=utf-8",Json.stringify(new AnalysisService().analyze(source)));
      } catch (PayloadTooLargeException e) { send(x,413,"application/json","{\"error\":\"Source exceeds the 1 MB limit.\"}"); }
    });
    server.createContext("/api/compile", x -> {
      if(!x.getRequestMethod().equalsIgnoreCase("POST")){send(x,405,"application/json","{\"error\":\"POST required\"}");return;}
      Path dir=null;
      try {
        String source=readSource(x);
        String cls=AnalysisService.findPrimaryClassName(source); if(cls==null)cls="Main";
        dir=Files.createTempDirectory("refactor-studio-");
        Path file=dir.resolve(cls+".java"); Files.writeString(file,source,StandardCharsets.UTF_8);
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null){send(x,500,"application/json",Json.stringify(Map.of("success",false,"output","JDK compiler unavailable; use JDK 21.")));return;}
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        int exit=compiler.run(null,output,output,"-encoding","UTF-8","-proc:none","--release","21","-d",dir.toString(),file.toString());
        String text=output.toString(StandardCharsets.UTF_8);
        send(x,200,"application/json; charset=utf-8",Json.stringify(Map.of("success",exit==0,"output",text.isBlank()?(exit==0?"Compilation successful.":"Compilation failed."):text)));
      } catch(PayloadTooLargeException e){send(x,413,"application/json",Json.stringify(Map.of("success",false,"output","Source exceeds the 1 MB limit.")));}
      catch(Exception e){send(x,500,"application/json",Json.stringify(Map.of("success",false,"output",String.valueOf(e.getMessage()))));}
      finally { if(dir!=null) try(var walk=Files.walk(dir)){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException ignored){} }
    });
    server.createContext("/api/refactor", x -> {
      if (!x.getRequestMethod().equalsIgnoreCase("POST")) {
        send(x,405,"application/json","{\"success\":false,\"message\":\"POST required\"}");
        return;
      }
      try {
        String source = readSource(x);
        Map<String,Object> result = new RefactoringEngine().propose(source);
        send(x,200,"application/json; charset=utf-8",Json.stringify(result));
      } catch (PayloadTooLargeException e) { send(x,413,"application/json","{\"success\":false,\"message\":\"Source exceeds the 1 MB limit.\"}"); }
    });
    server.createContext("/api/refactor/rules", x -> {
      if (!x.getRequestURI().getPath().equals("/api/refactor/rules")) { send(x,404,"application/json","{\"error\":\"Not found\"}"); return; }
      if (!x.getRequestMethod().equalsIgnoreCase("GET")) { send(x,405,"application/json","{\"error\":\"GET required\"}"); return; }
      send(x,200,"application/json; charset=utf-8",Json.stringify(new RefactoringEngine().availableRules()));
    });
    server.createContext("/api/refactor/proposals", x -> {
      if (!x.getRequestURI().getPath().equals("/api/refactor/proposals")) { send(x,404,"application/json","{\"error\":\"Not found\"}"); return; }
      if (!x.getRequestMethod().equalsIgnoreCase("POST")) { send(x,405,"application/json","{\"error\":\"POST required\"}"); return; }
      try {
        String source = readSource(x);
        send(x,200,"application/json; charset=utf-8",Json.stringify(new RefactoringEngine().proposeAll(source)));
      } catch (PayloadTooLargeException e) { send(x,413,"application/json","{\"error\":\"Source exceeds the 1 MB limit.\"}"); }
    });
    server.createContext("/api/ai/models", x -> {
      if (!x.getRequestURI().getPath().equals("/api/ai/models")) { send(x,404,"application/json","{\"success\":false,\"message\":\"Not found\"}"); return; }
      if (!x.getRequestMethod().equalsIgnoreCase("GET")) { send(x,405,"application/json","{\"success\":false,\"message\":\"GET required\"}"); return; }
      try {
        send(x,200,"application/json; charset=utf-8",Json.stringify(Map.of("success",true,"models",new OllamaRefactoringService().models())));
      } catch (Exception e) {
        send(x,200,"application/json; charset=utf-8",Json.stringify(Map.of("success",false,"models",List.of(),"message","Local AI is unavailable. Start Ollama and install a code-capable model to enable AI proposals.")));
      }
    });
    server.createContext("/api/ai/refactor", x -> {
      if (!x.getRequestURI().getPath().equals("/api/ai/refactor")) { send(x,404,"application/json","{\"success\":false,\"message\":\"Not found\"}"); return; }
      if (!x.getRequestMethod().equalsIgnoreCase("POST")) { send(x,405,"application/json","{\"success\":false,\"message\":\"POST required\"}"); return; }
      try {
        String source = readSource(x);
        String model = x.getRequestHeaders().getFirst("X-Ollama-Model");
        String encodedGoal = x.getRequestHeaders().getFirst("X-Refactor-Goal");
        String goal = encodedGoal == null ? "" : URLDecoder.decode(encodedGoal, StandardCharsets.UTF_8);
        if (goal.length() > 500) { send(x,400,"application/json","{\"success\":false,\"message\":\"The refactoring goal must be 500 characters or fewer.\"}"); return; }
        Map<String,Object> result = new OllamaRefactoringService().propose(model,goal,source);
        send(x,200,"application/json; charset=utf-8",Json.stringify(result));
      } catch (PayloadTooLargeException e) {
        send(x,413,"application/json","{\"success\":false,\"message\":\"Source exceeds the 1 MB limit.\"}");
      } catch (IllegalArgumentException e) {
        send(x,400,"application/json",Json.stringify(Map.of("success",false,"message",String.valueOf(e.getMessage()))));
      } catch (Exception e) {
        String message = e instanceof java.net.ConnectException || e instanceof java.net.http.HttpTimeoutException
            ? "Could not reach local Ollama. Start it and check that the selected model is available."
            : String.valueOf(e.getMessage());
        send(x,503,"application/json; charset=utf-8",Json.stringify(Map.of("success",false,"message",message)));
      }
    });
    server.createContext("/api/project/validate", x -> {
      if (!x.getRequestURI().getPath().equals("/api/project/validate")) { send(x,404,"application/json","{\"error\":\"Not found\"}"); return; }
      if (!x.getRequestMethod().equalsIgnoreCase("POST")) { send(x,405,"application/json","{\"error\":\"POST required\"}"); return; }
      try {
        byte[] archive = readProjectArchive(x);
        Map<String,Object> result = new MavenProjectValidationService().validate(archive);
        send(x,200,"application/json; charset=utf-8",Json.stringify(result));
      } catch (PayloadTooLargeException e) {
        send(x,413,"application/json","{\"success\":false,\"message\":\"Project archive exceeds the 50 MB upload limit.\"}");
      } catch (IllegalArgumentException e) {
        send(x,400,"application/json; charset=utf-8",Json.stringify(Map.of("success",false,"message",String.valueOf(e.getMessage()))));
      } catch (Exception e) {
        send(x,500,"application/json; charset=utf-8",Json.stringify(Map.of("success",false,"message",String.valueOf(e.getMessage()))));
      }
    });
    server.start();
    System.out.println("Refactor Studio running at http://127.0.0.1:8765");
    System.out.println("Keep this window open; press Ctrl+C to stop.");
  }
  static void send(HttpExchange x,int code,String type,String body)throws IOException{
    byte[] b=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(code,b.length);try(OutputStream o=x.getResponseBody()){o.write(b);}
  }

  private static String readSource(HttpExchange exchange) throws IOException, PayloadTooLargeException {
    byte[] bytes = exchange.getRequestBody().readNBytes(MAX_SOURCE_BYTES + 1);
    if (bytes.length > MAX_SOURCE_BYTES) throw new PayloadTooLargeException();
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static byte[] readProjectArchive(HttpExchange exchange) throws IOException, PayloadTooLargeException {
    byte[] bytes = exchange.getRequestBody().readNBytes(MavenProjectValidationService.MAX_ARCHIVE_BYTES + 1);
    if (bytes.length > MavenProjectValidationService.MAX_ARCHIVE_BYTES) throw new PayloadTooLargeException();
    return bytes;
  }

  private static final class PayloadTooLargeException extends Exception { }
}
