package io.wyrmgate.iam.integration.provider.scim;

import io.wyrmgate.iam.integration.application.ConnectorExecutionRepository.ExecutionConfiguration;
import io.wyrmgate.iam.platform.tenant.TenantContext;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Deployment-owned permission to use one exact endpoint and opaque secret reference. */
@FunctionalInterface
public interface ScimExecutionPolicy {
    boolean permits(TenantContext tenant,ExecutionConfiguration config);
    record Route(UUID tenant,UUID connector,URI baseUri,String reference){}
    static ScimExecutionPolicy configured(String declaration){
        Set<Route> routes=new HashSet<>();
        for(String entry:declaration.split(";")){
            if(entry.isBlank())continue;
            String[] fields=entry.trim().split("\\|",-1);
            if(fields.length!=4)throw new IllegalArgumentException("invalid SCIM route authorization");
            URI uri=approvedUri(fields[2]);
            if(!fields[3].matches("env:IAM_CONNECTOR_[A-Za-z0-9_]+"))throw new IllegalArgumentException("invalid SCIM secret reference");
            routes.add(new Route(UUID.fromString(fields[0]),UUID.fromString(fields[1]),uri,fields[3]));
        }
        Set<Route> approved=Set.copyOf(routes);
        return (tenant,config)->{
            try {
                Object raw=config.configuration().get("baseUri");
                if(!(raw instanceof String base))return false;
                return approved.contains(new Route(tenant.tenantId(),config.connectorInstanceId(),approvedUri(base),config.secretReference()));
            }catch(IllegalArgumentException invalid){return false;}
        };
    }
    private static URI approvedUri(String value){
        URI uri=URI.create(value);
        if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null
            ||uri.getQuery()!=null||uri.getFragment()!=null||!uri.normalize().equals(uri))
            throw new IllegalArgumentException("SCIM endpoint must be an approved HTTPS base URI");
        return uri;
    }
}
