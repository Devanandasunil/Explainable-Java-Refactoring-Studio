package com.semanticrefactoring;
import java.util.*;
public final class Json {
  private Json(){}
  public static Object parse(String input){return new Parser(input).parse();}
  public static String stringify(Object v){
    if(v==null)return "null";
    if(v instanceof String s)return "\""+esc(s)+"\"";
    if(v instanceof Number||v instanceof Boolean)return v.toString();
    if(v instanceof Map<?,?> m){StringJoiner j=new StringJoiner(",","{","}");m.forEach((k,val)->j.add(stringify(String.valueOf(k))+":"+stringify(val)));return j.toString();}
    if(v instanceof Iterable<?> it){StringJoiner j=new StringJoiner(",","[","]");for(Object x:it)j.add(stringify(x));return j.toString();}
    return stringify(v.toString());
  }
  private static String esc(String s){StringBuilder b=new StringBuilder();for(char c:s.toCharArray())switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}return b.toString();}

  private static final class Parser {
    private final String text;
    private int pos;
    Parser(String text){this.text=Objects.requireNonNull(text);}
    Object parse(){Object value=value();space();if(pos!=text.length())fail("Unexpected content");return value;}
    private Object value(){space();if(pos>=text.length())return fail("Expected a value");char c=text.charAt(pos);if(c=='"')return string();if(c=='{')return object();if(c=='[')return array();if(c=='t'&&literal("true"))return true;if(c=='f'&&literal("false"))return false;if(c=='n'&&literal("null"))return null;if(c=='-'||(c>='0'&&c<='9'))return number();return fail("Invalid value");}
    private Map<String,Object> object(){pos++;Map<String,Object> map=new LinkedHashMap<>();space();if(take('}'))return map;while(true){space();if(pos>=text.length()||text.charAt(pos)!='"')return fail("Expected object key");String key=string();space();if(!take(':'))return fail("Expected ':'");map.put(key,value());space();if(take('}'))return map;if(!take(','))return fail("Expected ',' or '}'");}}
    private List<Object> array(){pos++;List<Object> list=new ArrayList<>();space();if(take(']'))return list;while(true){list.add(value());space();if(take(']'))return list;if(!take(','))return fail("Expected ',' or ']'");}}
    private String string(){pos++;StringBuilder b=new StringBuilder();while(pos<text.length()){char c=text.charAt(pos++);if(c=='"')return b.toString();if(c!='\\'){if(c<32)return fail("Control character in string");b.append(c);continue;}if(pos>=text.length())return fail("Incomplete escape");char e=text.charAt(pos++);switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(pos+4>text.length())return fail("Incomplete unicode escape");try{b.append((char)Integer.parseInt(text.substring(pos,pos+4),16));}catch(NumberFormatException ex){return fail("Invalid unicode escape");}pos+=4;}default->{return fail("Invalid escape");}}}return fail("Unterminated string");}
    private Number number(){int start=pos;if(take('-')&&pos>=text.length())return fail("Invalid number");if(take('0')){}else{if(!digit())return fail("Invalid number");while(digit())pos++;}if(take('.')){if(!digit())return fail("Invalid fraction");while(digit())pos++;}if(pos<text.length()&&(text.charAt(pos)=='e'||text.charAt(pos)=='E')){pos++;if(pos<text.length()&&(text.charAt(pos)=='+'||text.charAt(pos)=='-'))pos++;if(!digit())return fail("Invalid exponent");while(digit())pos++;}String n=text.substring(start,pos);try{return n.indexOf('.')>=0||n.indexOf('e')>=0||n.indexOf('E')>=0?Double.valueOf(n):Long.valueOf(n);}catch(NumberFormatException ex){return fail("Invalid number");}}
    private boolean digit(){return pos<text.length()&&text.charAt(pos)>='0'&&text.charAt(pos)<='9';}
    private boolean literal(String value){if(!text.startsWith(value,pos))return false;pos+=value.length();return true;}
    private boolean take(char c){if(pos<text.length()&&text.charAt(pos)==c){pos++;return true;}return false;}
    private void space(){while(pos<text.length()&&(text.charAt(pos)==' '||text.charAt(pos)=='\n'||text.charAt(pos)=='\r'||text.charAt(pos)=='\t'))pos++;}
    private <T>T fail(String message){throw new IllegalArgumentException(message+" at JSON offset "+pos);}
  }
}
