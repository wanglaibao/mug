package org.mu.util;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A funnel that dispatches a sequence of inputs through arbitrary batch conversions while
 * maintaining first-in-first-out order. For example, the following code can either batch load users
 * from a user store, or batch load from third party user store, or else create a dummy user
 * immediately without conversion:
 *
 * <pre>{@code
 * Funnel<User> funnel = new Funnel<>();
 * Funnel.Batch<Long, User> userStoreBatch = funnel.through(userStore::loadUsers);
 * Funnel.Batch<ThirdPartyUser, User> thirdPartyBatch = funnel.through(thirdPartyClient::loadUsers);
 * for (UserDto dto : users) {
 *   if (dto.hasUserId()) {
 *     userStoreBatch.accept(dto.getUserId());
 *   } else if (dto.hasThirdParty()) {
 *     thirdPartyBatch.accept(dto.getThirdParty());
 *   } else {
 *     funnel.add(createDummyUser(dto));
 *   }
 * }
 * List<User> users = funnel.run();
 * }</pre>
 *
 * <p>Elements flow out of the funnel in the same order as they enter, regardless of which {@link
 * Consumer} instance admitted them, or if they were directly {@link #add added} into the
 * funnel without conversion.
 */
public final class Funnel<T> {

  private int size = 0;
  private final List<Batch<?, T>> batches = new ArrayList<>();
  private final Batch<T, T> passthrough = through(x -> x);

  /** Holds the elements to be converted through a batch conversion. */
  public static final class Batch<F, T> {
    private final Funnel<T> funnel;
    private final Function<? super List<F>, ? extends Collection<? extends T>> converter;
    private final List<Indexed<F, T>> indexedSources = new ArrayList<>();

    Batch(
        Funnel<T> funnel, Function<? super List<F>, ? extends Collection<? extends T>> converter) {
      this.funnel = funnel;
      this.converter = requireNonNull(converter);
    }

    /** Adds {@code source} to be converted. */
    public void accept(F source) {
      accept(source, v -> v);
    }

    /**
     * Adds {@code source} to be converted.
     * {@code postConversion} will be applied after the batch conversion completes,
     * to compute the final result for this input.
     */
    public void accept(F source, Function<? super T, ? extends T> postConversion) {
      indexedSources.add(new Indexed<>(funnel.size++, source, postConversion));
    }

    void convertInto(ArrayList<T> output) {
      if (indexedSources.isEmpty()) {
        return;
      }
      List<F> params = indexedSources.stream()
          .map(i -> i.value)
          .collect(toList());
      List<T> results = new ArrayList<>(converter.apply(params));
      if (params.size() != results.size()) {
        throw new IllegalStateException(
            converter + " expected to return " + params.size() + " elements for input "
                + params + ", but got " + results + " of size " + results.size() + ".");
      }
      for (int i = 0; i < indexedSources.size(); i++) {
        Indexed<F, T> source = indexedSources.get(i);
        output.set(source.index, source.converter.apply(results.get(i)));
      }
    }
  }

  /**
   * Returns a {@link Consumer} instance accepting elements that, when {@link #run} is called,
   * will be converted together in a batch through {@code converter}.
   */
  public <F> Batch<F, T> through(
      Function<? super List<F>, ? extends Collection<? extends T>> converter) {
    Batch<F, T> batch = new Batch<>(this, converter);
    batches.add(batch);
    return batch;
  }

  /** Adds {@code element} to the funnel. */
  public void add(T element) {
    passthrough.accept(element);
  }

  /**
   * Runs all batch conversions and returns conversion results together with elements {@link #add
   * added} as is, in encounter order.
   */
  public List<T> run() {
    ArrayList<T> output = new ArrayList<>(Collections.nCopies(size, null));
    for (Batch<?, T> batch : batches) {
      batch.convertInto(output);
    }
    return output;
  }

  private static final class Indexed<F, T> {
    final int index;
    final F value;
    final Function<? super T, ? extends T> converter;

    Indexed(int index, F value, Function<? super T, ? extends T> converter) {
      this.index = index;
      this.value = requireNonNull(value);
      this.converter = requireNonNull(converter);
    }
  }
}