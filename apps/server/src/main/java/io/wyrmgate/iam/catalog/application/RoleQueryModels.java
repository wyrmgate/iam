package io.wyrmgate.iam.catalog.application;

import io.wyrmgate.iam.catalog.application.CatalogQueryModels.PagePosition;
import io.wyrmgate.iam.catalog.domain.Role;
import io.wyrmgate.iam.catalog.domain.RoleVersion;
import io.wyrmgate.iam.catalog.domain.RoleVersionMember;
import java.util.List;

public final class RoleQueryModels {
    private RoleQueryModels() {}

    public record RolePage(List<Role> items, PagePosition nextPosition) {
        public RolePage {
            items = List.copyOf(items);
        }
    }

    public record RoleVersionPage(
            List<RoleVersion> items,
            PagePosition nextPosition) {
        public RoleVersionPage {
            items = List.copyOf(items);
        }
    }

    public record RoleVersionDetail(
            RoleVersion version,
            List<RoleVersionMember> members) {
        public RoleVersionDetail {
            members = List.copyOf(members);
        }
    }
}
