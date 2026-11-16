package io.wyrmgate.iam.integration.provider.scim;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ScimDiscoveryCoverageTest {
    @Test void malformedEmptyPageCannotProveAbsence() {
        var coverage=new ScimDiscoveryCoverage(null);
        assertThat(coverage.accept(Map.of("totalResults",2,"startIndex",1,"itemsPerPage",0),List.of(),1,2).valid()).isFalse();
        assertThat(coverage.complete()).isFalse();
    }
    @Test void repeatedIdsAndChangedTotalsCannotProveCompleteness() {
        var repeated=new ScimDiscoveryCoverage(null);
        repeated.accept(Map.of("totalResults",2,"startIndex",1,"itemsPerPage",1),List.of(Map.of("id","a")),1,1);
        assertThat(repeated.accept(Map.of("totalResults",2,"startIndex",2,"itemsPerPage",1),List.of(Map.of("id","a")),2,1).end()).isTrue();
        assertThat(repeated.complete()).isFalse();
        var changed=new ScimDiscoveryCoverage(null);
        changed.accept(Map.of("totalResults",2,"startIndex",1,"itemsPerPage",1),List.of(Map.of("id","a")),1,1);
        assertThat(changed.accept(Map.of("totalResults",3,"startIndex",2,"itemsPerPage",1),List.of(Map.of("id","b")),2,1).valid()).isFalse();
        assertThat(changed.complete()).isFalse();
    }
    @Test void checkpointOneNeverProvesFullCoverageAndEmptyFullCan() {
        var resumed=new ScimDiscoveryCoverage("1");
        assertThat(resumed.accept(Map.of("totalResults",0),List.of(),1,2).end()).isTrue();
        assertThat(resumed.complete()).isFalse();
        var full=new ScimDiscoveryCoverage(null);
        assertThat(full.accept(Map.of("totalResults",0),List.of(),1,2).end()).isTrue();
        assertThat(full.complete()).isTrue();
    }
}
