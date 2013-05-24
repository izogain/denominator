package denominator;

import static com.google.common.base.Functions.toStringFunction;
import static com.google.common.collect.Lists.transform;
import static denominator.CredentialsConfiguration.anonymous;
import static denominator.CredentialsConfiguration.checkValidForProvider;
import static denominator.CredentialsConfiguration.credentials;
import static denominator.Denominator.create;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.testng.annotations.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

import dagger.Provides;
import denominator.Credentials.ListCredentials;
import denominator.config.GeoUnsupported;
import denominator.config.NothingToClose;
import denominator.config.OnlyNormalResourceRecordSets;
import denominator.mock.MockResourceRecordSetApi;
import denominator.model.ResourceRecordSet;

@Test
public class DynamicCredentialsProviderExampleTest {
    /**
     * example of domain-specific credentials.
     */
    static class CustomerUsernamePassword {
        private final String customer;
        private final String username;
        private final String password;

        private CustomerUsernamePassword(String customer, String username, String password) {
            this.customer = customer;
            this.username = username;
            this.password = password;
        }
    }

    /**
     * in this example, the zone api lazy uses domain-specific credentials on
     * each invocation.
     */
    static class DynamicCredentialsZoneApi implements ZoneApi {
        private final javax.inject.Provider<CustomerUsernamePassword> creds;

        /**
         * Supplier facilitates dynamic credentials.
         */
        @Inject
        DynamicCredentialsZoneApi(javax.inject.Provider<CustomerUsernamePassword> creds) {
            this.creds = creds;
        }

        /**
         * make sure you note in your javadoc that IllegalArgumentException can
         * arise when the user supplies the incorrect form of credentials.
         */
        @Override
        public Iterator<String> list() {
            // IllegalArgumentException is possible on lazy get
            CustomerUsernamePassword cup = creds.get();
            // normally, the credentials object would be used to invoke a remote
            // command. in this case, we don't and say we did :)
            return ImmutableList.of(cup.customer, cup.username, cup.password).iterator();
        }
    }

    final static class DynamicCredentialsProvider extends BasicProvider {
        
        /**
         * override name as default would be {@code dynamiccredentials} based on the class name.
         */
        @Override
        public String getName() {
            return "dynamic";
        }

        /**
         * inform the user that we need credentials with 3 parameters.
         */
        @Override
        public Multimap<String, String> getCredentialTypeToParameterNames() {
            return ImmutableMultimap.<String, String> builder()
                    .putAll("username", "customer", "username", "password").build();
        }

        // incomplete as it requires credentials to be bound externally
        @dagger.Module(injects = DNSApiManager.class,
                       includes = { NothingToClose.class,
                                    GeoUnsupported.class,
                                    OnlyNormalResourceRecordSets.class } )
        static class Module {

            @Provides
            public Provider provider() {
                return new DynamicCredentialsProvider();
            }

            /**
             * simulates remote credential management where credentials can change
             * across requests, or accidentally return null.
             */
            @Provides
            Credentials dynamicCredentials() {
                int index = credentialIndex.getAndIncrement();
                if (credentials.size() < (index + 1))
                    return null; // simulate no credentials
                List<String> current = credentials.get(index);
                return ListCredentials.from(current);
            }

            final AtomicInteger credentialIndex = new AtomicInteger();
            final List<List<String>> credentials = ImmutableList.<List<String>> builder()
                    .add(ImmutableList.of("acme", "wily", "coyote"))
                    .add(ImmutableList.of("acme", "road", "runner"))
                    .build();

            /**
             * wiring of the zone api requires a Supplier as otherwise credentials
             * would not be able to dynamically update.
             */
            @Provides
            @Singleton
            ZoneApi provideZoneApi(javax.inject.Provider<CustomerUsernamePassword> creds) {
                return new DynamicCredentialsZoneApi(creds);
            }
    
            /**
             * using mock as example case is made already with the zone api
             */
            @Provides
            ResourceRecordSetApi.Factory provideResourceRecordSetApiFactory(MockResourceRecordSetApi.Factory in) {
                return in;
            }
    
            // wildcard types are not currently injectable in dagger
            @SuppressWarnings("rawtypes")
            @Provides
            @Singleton
            Multimap<String, ResourceRecordSet> provideData() {
                return ImmutableMultimap.of();
            }
    
            /**
             * @param credsProvider
             *            expected to return customer, username, and password in
             *            correct order
             */
            @Provides
            CustomerUsernamePassword fromListCredentials(Provider provider,
                    javax.inject.Provider<denominator.Credentials> credsProvider) {
                Credentials creds = checkValidForProvider(credsProvider.get(), provider);
                List<String> strings = transform(ListCredentials.class.cast(creds), toStringFunction());
                return new CustomerUsernamePassword(strings.get(0), strings.get(1), strings.get(2));
            }
        }
    }

    @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = "no credentials supplied. dynamic requires customer, username, password")
    public void testCredentialsRequired() {
        // shouldn't exception here, as credentials aren't yet used
        DNSApiManager mgr = create(new DynamicCredentialsProvider(), anonymous());
        // shouldn't exception here, as credentials aren't yet used
        ZoneApi zoneApi = mgr.getApi().getZoneApi();
        zoneApi.list();
    }

    @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = "incorrect credentials supplied. dynamic requires customer, username, password")
    public void testThreePartCredentialsRequired() {
        // shouldn't exception here, as credentials aren't yet used
        DNSApiManager mgr = create(new DynamicCredentialsProvider(), credentials("username", "password"));
        // shouldn't exception here, as credentials aren't yet used
        ZoneApi zoneApi = mgr.getApi().getZoneApi();
        zoneApi.list();
    }

    @Test
    public void testImplicitDynamicCredentialsUpdate() {
        DNSApiManager mgr = create(new DynamicCredentialsProvider());
        ZoneApi zoneApi = mgr.getApi().getZoneApi();
        assertEquals(ImmutableList.copyOf(zoneApi.list()), ImmutableList.of("acme", "wily", "coyote"));
        assertEquals(ImmutableList.copyOf(zoneApi.list()), ImmutableList.of("acme", "road", "runner"));
        // now, if the supplier doesn't supply a set of credentials, we should
        // get a correct message
        try {
            ImmutableList.copyOf(zoneApi.list());
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals(e.getMessage(), "no credentials supplied. dynamic requires customer, username, password");
        }
    }

}
