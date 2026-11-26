// Visibility policy model used by the daemon and tests.
package com.cairn.visibility;

import java.util.*;

public final class VisibilityPolicy {
    public record Rule(int userId, String caller, String target) {
        public Rule {
            if (userId < 0 || !valid(caller) || !valid(target) || caller.equals(target))
                throw new IllegalArgumentException("Invalid visibility rule");
        }
    }
    
    public record Identity(int uid, Set<String> packages, boolean privileged) {
        public Identity { packages = Set.copyOf(packages); }
    }
    private final Set<Rule> rules;
    private final Set<String> protectedPackages;
    public VisibilityPolicy(Collection<Rule> rules, Collection<String> protectedPackages) {
        if (rules.size() > 4096) throw new IllegalArgumentException("Too many rules");
        this.rules = Set.copyOf(rules);
        HashSet<String> protectedSet = new HashSet<>(protectedPackages);
        Collections.addAll(protectedSet,"android","com.android.systemui","com.cairn.manager");
        this.protectedPackages = Set.copyOf(protectedSet);
    }
    public boolean shouldHide(Identity caller, int targetUser, String target) {
        if (caller == null || caller.uid() < 0 || targetUser < 0 || !valid(target)) return false;
        int appId = caller.uid() % 100000;
        if (appId < 10000 || appId > 19999 || caller.privileged()) return false;
        if (caller.uid() / 100000 != targetUser) return false;
        
        if (caller.packages().size() != 1) return false;
        String pkg = caller.packages().iterator().next();
        if (protectedPackages.contains(pkg) || protectedPackages.contains(target) || pkg.equals(target)) return false;
        return rules.contains(new Rule(targetUser,pkg,target));
    }
    
    public boolean filter(boolean platformFiltered, Identity caller, int targetUser, String target) {
        return platformFiltered || shouldHide(caller,targetUser,target);
    }
    private static boolean valid(String name) {
        return name != null && name.length() <= 255 && name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }
}
