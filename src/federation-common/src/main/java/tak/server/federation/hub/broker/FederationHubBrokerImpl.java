package tak.server.federation.hub.broker;

import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.apache.ignite.services.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bbn.roger.fig.FederationUtils;
import com.google.common.base.Strings;

import tak.server.federation.hub.FederationHubDependencyInjectionProxy;
import tak.server.federation.hub.broker.events.UpdatePolicy;
import tak.server.federation.hub.policy.FederationHubPolicyManager;
import tak.server.federation.hub.ui.graph.FederationOutgoingCell;
import tak.server.federation.hub.ui.graph.FederationPolicyModel;
import tak.server.federation.hub.ui.graph.PolicyObjectCell;

public class FederationHubBrokerImpl implements FederationHubBroker, Service {

    private static final long serialVersionUID = -4468694862348986215L;

    private static final Logger logger = LoggerFactory.getLogger(FederationHubBrokerImpl.class);

    public static String getCN(String dn) throws RuntimeException {
        if (Strings.isNullOrEmpty(dn)) {
            throw new IllegalArgumentException("empty DN");
        }

        try {
            LdapName ldapName = new LdapName(dn);

            for (Rdn rdn : ldapName.getRdns()) {
                if (rdn.getType().equalsIgnoreCase("CN")) {
                    return rdn.getValue().toString();
                }
            }

            throw new RuntimeException("No CN found in DN: " + dn);
        } catch (InvalidNameException e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void saveTruststoreFile(SSLConfig sslConfig,
            FederationHubServerConfig fedHubConfig) {
        try {

            if (Strings.isNullOrEmpty(fedHubConfig.getTruststorePassword())) {
                throw new IllegalArgumentException("empty or null truststore password ");
            }
            FileOutputStream fos = new FileOutputStream(
                fedHubConfig.getTruststoreFile());
            sslConfig.getTrust().store(fos,
                fedHubConfig.getTruststorePassword().toCharArray());
            fos.close();
            logger.trace("Federation Hub truststore file save complete");
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IllegalArgumentException | IOException e) {
            logger.error("Exception saving Federation Hub truststore file", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void addGroupCa(X509Certificate ca) {
        FederationHubDependencyInjectionProxy depProxy =
            FederationHubDependencyInjectionProxy.getInstance();
        SSLConfig sslConfig = depProxy.sslConfig();
        FederationHubPolicyManager fedHubPolicyManager =
            depProxy.fedHubPolicyManager();
        FederationHubServerConfig fedHubConfig =
            depProxy.fedHubServerConfig();

        try {
            String dn = ca.getSubjectX500Principal().getName();
            String alias = FederationUtils.getBytesSHA256(ca.getEncoded());
                        
            sslConfig.getTrust().setEntry(alias, new KeyStore.TrustedCertificateEntry(ca), null);
            saveTruststoreFile(sslConfig, fedHubConfig);
            sslConfig.refresh();
            FederationHubBrokerUtils.sendCaGroupToFedManager(fedHubPolicyManager, ca);
            depProxy.restartV2Server();
        } catch (KeyStoreException | RuntimeException | CertificateEncodingException e) {
            logger.error("Exception adding CA", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void cancel() {
        if (logger.isDebugEnabled()) {
            logger.debug("cancel() in " + getClass().getName());
        }
    }

    @Override
    public void init() throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("init() in " + getClass().getName());
        }
    }

    @Override
    public void execute() throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("execute() in " + getClass().getName());
        }
    }

	@Override
	public void updatePolicy(FederationPolicyModel federationPolicyModel) {
		Collection<PolicyObjectCell> cells = federationPolicyModel.getCells();
		
		if (cells != null) {
			List<FederationOutgoingCell> outgoings = new ArrayList<>();
			cells.stream().filter(cell -> cell instanceof FederationOutgoingCell).forEach( cell -> {
				outgoings.add((FederationOutgoingCell) cell);
	        });
			
			FederationHubDependencyInjectionProxy.getSpringContext().publishEvent(new UpdatePolicy(this, outgoings));
		}
	}

	@Override
	public List<HubConnectionInfo> getActiveConnections() {
		return FederationHubDependencyInjectionProxy.getInstance()
			.hubConnectionStore()
			.getClientStreamMap()
			.values()
			.stream()
			.filter(holder -> holder.isClientHealthy(FederationHubDependencyInjectionProxy.getInstance().fedHubServerConfig().getClientTimeoutTime()))
			.filter(holder -> holder.getSubscription() != null)
			.map(holder -> {
				HubConnectionInfo info = new HubConnectionInfo();
				info.setConnectionId(holder.getFederateIdentity().getFedId());
				info.setConnectionType(holder.getSubscription().getIdentity().getType().toString());
				return info;
			})
			.collect(Collectors.toList());
	}
}
