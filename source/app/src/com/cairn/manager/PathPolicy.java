// Client for reading and writing per-UID path visibility rules.
package com.cairn.manager;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class PathPolicy {
 static final String PREFS="path_policy_v1";
 static final int MAX=32, PATH_BYTES=256, TABLE_BYTES=8720;
 static List<String> parse(String text) {
  LinkedHashSet<String> paths=new LinkedHashSet<>();
  for(String line:text.split("\n",-1)) {
   if(line.isEmpty())continue;
   byte[] bytes=line.getBytes(StandardCharsets.UTF_8);
   if(bytes.length<2 || bytes.length>=PATH_BYTES || !line.startsWith("/") || line.endsWith("/"))throw new IllegalArgumentException("Invalid absolute path");
   for(int i=0;i<line.length();i++){
    char ch=line.charAt(i);
    if(ch<32 || ch==127 || Character.isLowSurrogate(ch))throw new IllegalArgumentException("Invalid path characters");
    if(Character.isHighSurrogate(ch)){
     if(i+1>=line.length() || !Character.isLowSurrogate(line.charAt(i+1)))throw new IllegalArgumentException("Invalid path characters");
     i++;
    }
   }
   for(String part:line.substring(1).split("/",-1))if(part.isEmpty() || part.equals(".") || part.equals(".."))throw new IllegalArgumentException("Invalid path component");
   paths.add(line);
  }
  if(paths.size()>MAX)throw new IllegalArgumentException("Too many rules");
  return new ArrayList<>(paths);
 }
 static String saved(Context context,int uid){return context.getSharedPreferences(PREFS,0).getString(Integer.toString(uid),"");}
 static byte[] encode(Map<Integer,List<String>> rules){
  int count=0;for(Map.Entry<Integer,List<String>> entry:rules.entrySet()){
   if(entry.getKey()<10000)throw new IllegalArgumentException("Invalid application UID");
   count+=entry.getValue().size();
  }
  if(count>MAX)throw new IllegalArgumentException("Too many rules");
  ByteBuffer table=ByteBuffer.allocate(TABLE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
  table.putInt(1).putInt(TABLE_BYTES).putInt(count).putInt(0);
  for(int uid:new TreeSet<>(rules.keySet()))for(String path:rules.get(uid)){
   parse(path);byte[] bytes=path.getBytes(StandardCharsets.UTF_8);
   int offset=table.position();table.putInt(uid).putInt(1).putInt(bytes.length).putInt(0).put(bytes);
   table.position(offset+272);
  }
  return table.array();
 }
 static String quote(String text){return "'"+text.replace("'","'\\''")+"'";}
 static Map<Integer,List<String>> readOfficial(Context context,CairnCore core,long k0,long k1)throws Exception {
  CairnCore.Res result=core.runRoot(k0,k1,"/data/adb/cnd path-hide export-hex\nrc=$?\nexit $rc\n");
  if(result.rc!=0){
   if(Arrays.asList(result.text().split("\n")).contains("PATH_ERROR=saved-rules-missing"))return new TreeMap<>();
   throw new IllegalStateException("Cannot read official rules: rc="+result.rc);
  }
  String hex=null;for(String line:result.text().split("\n"))if(line.startsWith("PATH_RULES_HEX="))hex=line.substring(15);
  if(hex==null || hex.length()!=TABLE_BYTES*2)throw new IllegalArgumentException("Invalid rule export");
  byte[] bytes=new byte[TABLE_BYTES];for(int i=0;i<bytes.length;i++){
   int high=Character.digit(hex.charAt(i*2),16),low=Character.digit(hex.charAt(i*2+1),16);
   if(high<0 || low<0)throw new IllegalArgumentException("Invalid rule hex");bytes[i]=(byte)(high*16+low);
  }
  ByteBuffer table=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
  if(table.getInt()!=1 || table.getInt()!=TABLE_BYTES)throw new IllegalArgumentException("Invalid rule header");
  int count=table.getInt();if(count<0 || count>MAX || table.getInt()!=0)throw new IllegalArgumentException("Invalid rule count");
  Map<Integer,List<String>> rules=new TreeMap<>();
  for(int i=0;i<count;i++){
   int start=16+i*272;table.position(start);int uid=table.getInt(),flags=table.getInt(),length=table.getInt(),reserved=table.getInt();
   if(uid<10000 || flags!=1 || reserved!=0 || length<2 || length>=256)throw new IllegalArgumentException("Unsupported rule: refusing destructive replacement");
   byte[] path=new byte[length];table.get(path);if(table.get()!=0)throw new IllegalArgumentException("Invalid path terminator");
   String text=new String(path,StandardCharsets.UTF_8);parse(text);rules.computeIfAbsent(uid,key->new ArrayList<>()).add(text);
  }
  return rules;
 }
 static synchronized CairnCore.Res apply(Context context,CairnCore core,long k0,long k1,int uid,String edited){

  try{
   SharedPreferences prefs=context.getSharedPreferences(PREFS,0);
   Map<Integer,List<String>> rules=readOfficial(context,core,k0,k1);
   List<String> paths=parse(edited);rules.put(uid,paths);
   byte[] bytes=encode(rules);
   StringBuilder hex=new StringBuilder(bytes.length*2);
   final char[] digits="0123456789abcdef".toCharArray();
   for(byte value:bytes){hex.append(digits[(value&255)>>>4]);hex.append(digits[value&15]);}
   CairnCore.Res result=core.runRoot(k0,k1,"/data/adb/cnd path-hide apply-hex-stdin <<'CAIRN_RULE_HEX'\n"+hex+"\nCAIRN_RULE_HEX\nrc=$?\necho PATH_CLI_EXIT=$rc\nexit $rc\n");
   if(result.rc!=0 || !Arrays.asList(result.text().split("\n")).contains("PATH_APPLIED=true") || !Arrays.asList(result.text().split("\n")).contains("PATH_PERSISTED=true"))return new CairnCore.Res(result.rc==0?-6:result.rc,result.text());
   String canonical=String.join("\n",paths);
   if(!prefs.edit().putString(Integer.toString(uid),canonical).commit())return new CairnCore.Res(-7,"PATH_APPLIED=true\nPATH_CONFIG_SAVE_FAILED\n");
   return result;
  }catch(Exception error){return new CairnCore.Res(-8,"PATH_ERROR="+error.getClass().getSimpleName()+": "+error.getMessage());}
 }
 private PathPolicy(){}
}
