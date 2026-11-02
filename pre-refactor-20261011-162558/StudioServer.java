package com.semanticrefactoring;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

public class StudioServer {
  public static void main(String[] args) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8765), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/", x -> {
      if (!x.getRequestMethod().equalsIgnoreCase("GET")) { send(x,405,"text/plain","GET required"); return; }
      String p=x.getRequestURI().getPath(); if(p.equals("/"))p="/index.html";
      try(InputStream in=StudioServer.class.getResourceAsStream("/web"+p)){
        if(in==null){send(x,404,"text/plain","Not found");return;}
        String type=p.endsWith(".css")?"text/css; charset=utf-8":p.endsWith(".js")?"application/javascript; charset=utf-8":"text/html; charset=utf-8";
        send(x,200,type,new String(in.readAllBytes(),StandardCharsets.UTF_8));
      } catch(Exception e){send(x,500,"text/plain",e.toString());}
    });
    server.createContext("/api/analyze", x -> {
      if(!x.getRequestMethod().equalsIgnoreCase("POST")){send(x,405,"application/json","{\"error\":\"POST required\"}");return;}
      String source=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
      send(x,200,"application/json; charset=utf-8",Json.stringify(new AnalysisService().analyze(source)));
    });
    server.createContext("/api/compile", x -> {
      if(!x.getRequestMethod().equalsIgnoreCase("POST")){send(x,405,"application/json","{\"error\":\"POST required\"}");return;}
      Path dir=null;
      try {
        String source=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
        String cls=AnalysisService.findPrimaryClassName(source); if(cls==null)cls="Main";
        dir=Files.createTempDirectory("refactor-studio-");
        Path file=dir.resolve(cls+".java"); Files.writeString(file,source,StandardCharsets.UTF_8);
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null){send(x,500,"application/json",Json.stringify(Map.of("success",false,"output","JDK compiler unavailable; use JDK 21.")));return;}
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        int exit=compiler.run(null,output,output,"-encoding","UTF-8","-d",dir.toString(),file.toString());
        String text=output.toString(StandardCharsets.UTF_8);
        send(x,200,"application/json; charset=utf-8",Json.stringify(Map.of("success",exit==0,"output",text.isBlank()?(exit==0?"Compilation successful.":"Compilation failed."):text)));
      } catch(Exception e){send(x,500,"application/json",Json.stringify(Map.of("success",false,"output",String.valueOf(e.getMessage()))));}
      finally { if(dir!=null) try(var walk=Files.walk(dir)){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException ignored){} }
    });
    server.start();
    System.out.println("Refactor Studio running at http://127.0.0.1:8765");
    System.out.println("Keep this window open; press Ctrl+C to stop.");
  }
  static void send(HttpExchange x,int code,String type,String body)throws IOException{
    byte[] b=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(code,b.length);try(OutputStream o=x.getResponseBody()){o.write(b);}
  }
}
