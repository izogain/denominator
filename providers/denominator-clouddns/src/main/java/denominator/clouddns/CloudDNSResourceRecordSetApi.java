package denominator.clouddns;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterators.filter;
import static denominator.clouddns.RackspaceApis.emptyOn404;
import static denominator.model.ResourceRecordSets.nameEqualTo;

import java.net.URI;
import java.util.Iterator;

import javax.inject.Inject;

import com.google.common.base.Function;
import com.google.common.base.Optional;

import denominator.ResourceRecordSetApi;
import denominator.clouddns.RackspaceApis.CloudDNS;
import denominator.clouddns.RackspaceApis.ListWithNext;
import denominator.clouddns.RackspaceApis.Record;
import denominator.model.ResourceRecordSet;

class CloudDNSResourceRecordSetApi implements denominator.ResourceRecordSetApi {

    private final CloudDNS api;
    private final int domainId;

    CloudDNSResourceRecordSetApi(CloudDNS api, int domainId) {
        this.api = api;
        this.domainId = domainId;
    }

    @Override
    public Iterator<ResourceRecordSet<?>> iterator() {
        Function<URI, ListWithNext<Record>> recordPager = new Function<URI, ListWithNext<Record>>() {

            @Override
            public ListWithNext<Record> apply(URI nullOrNext) {
                return nullOrNext == null ? api.records(domainId) : api.records(nullOrNext);
            }

        };
        return new GroupByRecordNameAndTypeIterator(lazyIterateRecords(recordPager));
    }

    @Override
    public Iterator<ResourceRecordSet<?>> iterateByName(String name) {
        checkNotNull(name, "name was null");
        return filter(iterator(), nameEqualTo(name));
    }

    @Override
    public Optional<ResourceRecordSet<?>> getByNameAndType(final String name, final String type) {
        checkNotNull(name, "name was null");
        checkNotNull(type, "type was null");
        Function<URI, ListWithNext<Record>> recordPager = new Function<URI, ListWithNext<Record>>() {

            @Override
            public ListWithNext<Record> apply(URI nullOrNext) {
                return nullOrNext == null ? api.recordsByNameAndType(domainId, name, type) : api.records(nullOrNext);
            }

        };
        GroupByRecordNameAndTypeIterator it = new GroupByRecordNameAndTypeIterator(lazyIterateRecords(recordPager));
        return it.hasNext() ? Optional.<ResourceRecordSet<?>> of(it.next()) : Optional.<ResourceRecordSet<?>> absent();
    }

    @Override
    public void put(ResourceRecordSet<?> rrset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void deleteByNameAndType(String name, String type) {
        throw new UnsupportedOperationException();
    }

    static final class Factory implements denominator.ResourceRecordSetApi.Factory {

        private final CloudDNS api;

        @Inject
        Factory(CloudDNS api) {
            this.api = api;
        }

        @Override
        public ResourceRecordSetApi create(String id) {
            return new CloudDNSResourceRecordSetApi(api, Integer.parseInt(id));
        }
    }

    Iterator<Record> lazyIterateRecords(final Function<URI, ListWithNext<Record>> recordPager) {
        final ListWithNext<Record> first = emptyOn404(recordPager, null);
        if (first.next == null)
            return first.iterator();
        return new Iterator<Record>() {
            Iterator<Record> current = first.iterator();
            URI next = first.next;

            @Override
            public boolean hasNext() {
                while (!current.hasNext() && next != null) {
                    ListWithNext<Record> nextPage = emptyOn404(recordPager, next);
                    current = nextPage.iterator();
                    next = nextPage.next;
                }
                return current.hasNext();
            }

            @Override
            public Record next() {
                return current.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
