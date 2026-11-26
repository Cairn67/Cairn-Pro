// ART runtime probe used by the visibility loader tests.
package com.cairn.visibility;

public final class ArtProbe {
 private static native int nativeHandshake(int abi);
 public static void main(String[] args) {
  if(args.length!=2 || !args[0].startsWith("/")) {System.err.println("ART_USAGE absolute-library-path");System.exit(2);}
  try {
   java.io.PrintStream log=new java.io.PrintStream(new java.io.FileOutputStream(args[1],true),true,"UTF-8");
   System.setOut(log);System.setErr(log);
   System.out.println("ART_PROCESS_START");
   System.load(args[0]);
   if(nativeHandshake(1)!=0x43414952) throw new IllegalStateException("Native handshake mismatch");
   if(nativeHandshake(2)!=-1) throw new IllegalStateException("ABI rejection failed");
   System.out.println("ART_JNI_PASS cases=2");
   PolicyTest.main(new String[0]);
   System.out.println("ART_POLICY_INTEGRATION_PASS");
  } catch(Throwable error) {System.err.println("ART_JNI_FAILED");error.printStackTrace(System.err);System.exit(3);}
 }
}
