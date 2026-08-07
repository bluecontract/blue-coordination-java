package blue.coordination.engine.api;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.RandomAccess;

/** Immutable flattened view whose storage remains page-addressable. */
final class CoordinationPagedList<E> extends AbstractList<E>
        implements RandomAccess {

    private final List<List<E>> pages;
    private final int[] pageEnds;
    private final int size;

    CoordinationPagedList(List<List<E>> pages) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.pageEnds = new int[pages.size()];
        int count = 0;
        for (int index = 0; index < pages.size(); index++) {
            count = Math.addExact(count, pages.get(index).size());
            pageEnds[index] = count;
        }
        this.size = count;
    }

    @Override
    public E get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "index=" + index + ", size=" + size);
        }
        int low = 0;
        int high = pageEnds.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (index < pageEnds[middle]) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        int pageStart = low == 0 ? 0 : pageEnds[low - 1];
        return pages.get(low).get(index - pageStart);
    }

    @Override
    public int size() { return size; }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private int pageIndex;
            private int offset;

            @Override
            public boolean hasNext() {
                return pageIndex < pages.size();
            }

            @Override
            public E next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                E result = pages.get(pageIndex).get(offset);
                offset++;
                if (offset == pages.get(pageIndex).size()) {
                    pageIndex++;
                    offset = 0;
                }
                return result;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException(
                        "immutable paged list");
            }
        };
    }
}
