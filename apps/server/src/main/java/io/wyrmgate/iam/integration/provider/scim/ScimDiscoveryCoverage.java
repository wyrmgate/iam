package io.wyrmgate.iam.integration.provider.scim;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Conservative, bounded proof of a contiguous SCIM listing. Positive observations survive bad metadata. */
final class ScimDiscoveryCoverage {
    private static final int MAX_TRACKED_IDS=100_000;
    private final Set<String> seen=new HashSet<>();
    private Integer total;
    private boolean completeEligible;
    ScimDiscoveryCoverage(String checkpoint) { completeEligible=checkpoint==null; }
    record Page(boolean valid, boolean end, int next) {}
    Page accept(Map<String,Object> document,List<Map<String,Object>> resources,int requestedStart,int requestedCount) {
        Integer count=number(document.get("totalResults"));
        Integer start=number(document.get("startIndex"));
        Integer items=number(document.get("itemsPerPage"));
        if(count!=null&&count==0&&resources.isEmpty()) {
            if(start==null)start=requestedStart;
            if(items==null)items=0;
        }
        if(count==null||start==null||items==null||start!=requestedStart||items!=resources.size()
                ||items>requestedCount||count<0||(total!=null&&!total.equals(count))
                ||(items==0&&requestedStart<=count)
                ||(items>0&&(long)requestedStart+items-1>count)) {
            completeEligible=false;return new Page(false,false,requestedStart);
        }
        total=count;
        for(var resource:resources) {
            Object id=resource.get("id");
            if(!(id instanceof String value)||value.isBlank()||seen.size()>=MAX_TRACKED_IDS||!seen.add(value))
                completeEligible=false;
        }
        long next=(long)requestedStart+items;
        if(next>Integer.MAX_VALUE)return new Page(false,false,requestedStart);
        return new Page(true,next>count,(int)next);
    }
    void incomplete() { completeEligible=false; }
    boolean complete() { return completeEligible; }
    private static Integer number(Object value) {
        if(value instanceof Integer i&&i>=0)return i;
        if(value instanceof Long l&&l>=0&&l<=Integer.MAX_VALUE)return l.intValue();
        return null;
    }
}
