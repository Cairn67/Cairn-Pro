// Tests for the visibility policy.
package com.cairn.visibility;
import java.util.*;
public final class PolicyTest {
 static int cases;
 static void check(boolean actual,boolean expected){cases++;if(actual!=expected)throw new AssertionError("Case "+cases);}
 static VisibilityPolicy.Identity id(int uid,String... names){return new VisibilityPolicy.Identity(uid,Set.of(names),false);}
 public static void main(String[] args){
  String caller="com.cairn.mtvisibilitytest",target="bin.mt.plus";
  var policy=new VisibilityPolicy(List.of(new VisibilityPolicy.Rule(0,caller,target),new VisibilityPolicy.Rule(10,caller,target)),Set.of("com.android.settings"));
  check(policy.shouldHide(id(10360,caller),0,target),true);
  check(policy.shouldHide(id(10360,"com.example.other"),0,target),false);
  check(policy.shouldHide(id(10360,caller),0,"com.example.other"),false);
  check(policy.shouldHide(id(10360,caller),10,target),false);
  check(policy.shouldHide(id(1010360,caller),10,target),true);
  check(policy.shouldHide(id(1010360,caller),0,target),false);
  for(int uid:new int[]{0,1000,2000,9999,20000,99000,-1})check(policy.shouldHide(id(uid,caller),0,target),false);
  check(policy.shouldHide(id(10360,caller,"com.example.shared"),0,target),false);
  check(policy.shouldHide(id(10360),0,target),false);
  check(policy.shouldHide(new VisibilityPolicy.Identity(10360,Set.of(caller),true),0,target),false);
  check(policy.shouldHide(null,0,target),false);
  check(policy.shouldHide(id(10360,caller),0,null),false);
  check(policy.shouldHide(id(10360,caller),0,caller),false);
  check(policy.filter(true,id(10360,"com.example.other"),0,target),true);
  check(policy.filter(false,id(10360,caller),0,target),true);
  check(new VisibilityPolicy(List.of(),Set.of()).shouldHide(id(10360,caller),0,target),false);
  check(new VisibilityPolicy(List.of(new VisibilityPolicy.Rule(0,caller,"com.android.settings")),Set.of("com.android.settings")).shouldHide(id(10360,caller),0,"com.android.settings"),false);
  HashSet<String> mutable=new HashSet<>(Set.of(caller));var snapshot=new VisibilityPolicy.Identity(10360,mutable,false);mutable.clear();check(policy.shouldHide(snapshot,0,target),true);
  for(String bad:new String[]{"", "../a", "bin/mt.plus", "bin.mt.plus\n"}){boolean rejected=false;try{new VisibilityPolicy.Rule(0,caller,bad);}catch(IllegalArgumentException e){rejected=true;}check(rejected,true);}
  System.out.println("VISIBILITY_POLICY_PASS cases="+cases);
 }
}
