package com.semanticrefactoring;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AnalysisServiceTest {
 @Test void parsesSimpleClass(){var r=new AnalysisService().analyze("public class Demo { int x; public int getX(){ return x; } }");assertEquals(Boolean.TRUE,r.get("success"));assertEquals(1,((java.util.List<?>)r.get("types")).size());assertEquals(1,((java.util.List<?>)r.get("methods")).size());}
 @Test void reportsBadSyntax(){var r=new AnalysisService().analyze("public class {");assertEquals(Boolean.FALSE,r.get("success"));}
}
