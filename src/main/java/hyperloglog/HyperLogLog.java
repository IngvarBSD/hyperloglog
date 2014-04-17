package hyperloglog;

import java.nio.charset.Charset;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

public class HyperLogLog {

  public static final int MIN_P_VALUE = 4;
  public static final int MAX_P_VALUE = 16;

  // constants for SPARSE encoding
  public static final int P_PRIME_VALUE = 25;
  public static final int Q_PRIME_VALUE = 6;

  public static enum EncodingType {
    SPARSE, DENSE
  }

  // number of bits to address registers
  private final int p;

  // number of registers - 2^p
  private final int m;
  private float alphaMM;
  private final int chosenHashBits;

  // 8-bit registers.
  // TODO: This can further be space optimized using 6 bit registers as longest
  // run for long value (hashcode) can be maximum of 64.
  // Using p = 14,
  // space required for 8-bit registers = (2 ^ 14) * 8 = 16KB
  // space required for 8-bit registers = (2 ^ 14) * 6 = 12KB
  private HLLDenseRegister denseRegister;
  private HLLSparseRegister sparseRegister;

  // Good fast hash function suggested by Guava hashing for the specified bits
  // Default is MurmurHash3_128
  private final HashFunction hf;
  private HashCode hc;

  // counts are cached to avoid complex computation. If register value is updated
  // the count will be computed again.
  private long cachedCount;
  private boolean invalidateCount;

  private EncodingType encoding;
  private int encodingSwitchThreshold;

  private HyperLogLog(HyperLogLogBuilder hllBuilder) {
    if (hllBuilder.numRegisterIndexBits < MIN_P_VALUE
        || hllBuilder.numRegisterIndexBits > MAX_P_VALUE) {
      throw new IllegalArgumentException("p value should be between " + MIN_P_VALUE + " to "
          + MAX_P_VALUE);
    }
    this.p = hllBuilder.numRegisterIndexBits;
    this.m = 1 << p;

    // the threshold should be less than 12K bytes for p = 14.
    // The reason to divide by 5 is, in sparse mode after serialization the entries
    // in sparse map are compressed, and delta encoded as varints. The worst case
    // size of varints are 5 bytes. Hence, 12K/5 ~= 2400 entries in sparse map.
    if (hllBuilder.bitPacking) {
      this.encodingSwitchThreshold = ((m * 6) / 8) / 5;
    } else {
      // if bitpacking is disabled, all register values takes 8 bits and hence
      // we can be more flexible with the threshold. For p=14, 16K/5 = 3200 entries
      // in sparse map can be allowed.
      this.encodingSwitchThreshold = m / 3;
    }

    // we won't need hash functions beyond 128 bits.. in fact 64 bits itself is
    // more than sufficient
    if (hllBuilder.numHashBits > 128) {
      this.hf = Hashing.goodFastHash(128);
    } else {
      this.hf = Hashing.goodFastHash(hllBuilder.numHashBits);
    }
    this.chosenHashBits = hf.bits();
    initializeAlpha();
    this.cachedCount = -1;
    this.invalidateCount = false;
    this.encoding = hllBuilder.encoding;
    if (encoding.equals(EncodingType.SPARSE)) {
      this.sparseRegister = new HLLSparseRegister(p, P_PRIME_VALUE, Q_PRIME_VALUE);
      this.denseRegister = null;
    } else {
      this.sparseRegister = null;
      this.denseRegister = new HLLDenseRegister(p, hllBuilder.bitPacking);
    }
  }

  public static HyperLogLogBuilder builder() {
    return new HyperLogLogBuilder();
  }

  public static class HyperLogLogBuilder {
    private int numRegisterIndexBits = 14;
    private int numHashBits = 64;
    private EncodingType encoding = EncodingType.SPARSE;
    private boolean bitPacking = true;

    public HyperLogLogBuilder() {
    }

    public HyperLogLogBuilder setNumRegisterIndexBits(int b) {
      this.numRegisterIndexBits = b;
      return this;
    }

    public HyperLogLogBuilder setNumHashBits(int hb) {
      this.numHashBits = hb;
      return this;
    }

    public HyperLogLogBuilder setEncoding(EncodingType enc) {
      this.encoding = enc;
      return this;
    }

    public HyperLogLogBuilder enableBitPacking(boolean b) {
      this.bitPacking = b;
      return this;
    }

    public HyperLogLog build() {
      return new HyperLogLog(this);
    }
  }

  // see paper for alpha initialization.
  private void initializeAlpha() {
    if (chosenHashBits <= 16) {
      alphaMM = 0.673f;
    } else if (chosenHashBits <= 32) {
      alphaMM = 0.697f;
    } else if (chosenHashBits <= 64) {
      alphaMM = 0.709f;
    } else {
      alphaMM = 0.7213f / (float) (1 + 1.079f / m);
    }

    // For efficiency alpha is multiplied by m^2
    alphaMM = alphaMM * m * m;
  }

  public void addBoolean(boolean val) {
    hc = hf.newHasher().putBoolean(val).hash();
    add(getHashCode());
  }

  public void addByte(byte val) {
    hc = hf.newHasher().putByte(val).hash();
    add(getHashCode());
  }

  public void addBytes(byte[] val) {
    hc = hf.newHasher().putBytes(val).hash();
    add(getHashCode());
  }

  public void addBytes(byte[] val, int offset, int len) {
    hc = hf.newHasher().putBytes(val, offset, len).hash();
    add(getHashCode());
  }

  public void addShort(short val) {
    hc = hf.newHasher().putShort(val).hash();
    add(getHashCode());
  }

  public void addInt(int val) {
    hc = hf.newHasher().putInt(val).hash();
    add(getHashCode());
  }

  public void addLong(long val) {
    hc = hf.newHasher().putLong(val).hash();
    add(getHashCode());
  }

  public void addFloat(float val) {
    hc = hf.newHasher().putFloat(val).hash();
    add(getHashCode());
  }

  public void addDouble(double val) {
    hc = hf.newHasher().putDouble(val).hash();
    add(getHashCode());
  }

  public void addChar(char val) {
    hc = hf.newHasher().putChar(val).hash();
    add(getHashCode());
  }

  /**
   * Java's default charset will be used for strings.
   * @param val
   *          - input string
   */
  public void addString(String val) {
    hc = hf.newHasher().putString(val, Charset.defaultCharset()).hash();
    add(getHashCode());
  }

  public void addString(String val, Charset charset) {
    hc = hf.newHasher().putString(val, charset).hash();
    add(getHashCode());
  }

  private long getHashCode() {
    long hashcode = 0;
    if (chosenHashBits < 64) {
      hashcode = hc.asInt();
    } else {
      hashcode = hc.asLong();
    }
    return hashcode;
  }

  public void add(long hashcode) {
    if (encoding.equals(EncodingType.SPARSE)) {
      if (sparseRegister.add(hashcode)) {
        invalidateCount = true;
      }
      if (sparseRegister.getSize() > encodingSwitchThreshold) {
        encoding = EncodingType.DENSE;
        denseRegister = HyperLogLogUtils.sparseToDenseRegister(sparseRegister);
        sparseRegister = null;
        invalidateCount = true;
      }
    } else {
      if (denseRegister.add(hashcode)) {
        invalidateCount = true;
      }
    }
  }

  public long count() {

    // compute count only if the register values are updated else return the
    // cached count
    if (invalidateCount || cachedCount < 0) {
      if (encoding.equals(EncodingType.SPARSE)) {
        int mPrime = 1 << sparseRegister.getPPrime();
        cachedCount = linearCount(mPrime, mPrime - sparseRegister.getSize());
      } else {
        double sum = denseRegister.getSumInversePow2();
        long numZeros = denseRegister.getNumZeroes();

        // cardinality estimate from normalized bias corrected harmonic mean on
        // the registers
        cachedCount = (long) (alphaMM * (1.0 / sum));
        long pow = (long) Math.pow(2, chosenHashBits);

        // HLL algorithm shows stronger bias for values in (2.5 * m) range.
        // To compensate for this short range bias, linear counting is used for
        // values before this short range. The original paper also says similar
        // bias is seen for long range values due to hash collisions in range >1/30*(2^32)
        // For the default case, we do not have to worry about this long range bias
        // as the paper used 32-bit hashing and we use 64-bit hashing as default.
        // 2^64 values are too high to observe long range bias.
        if (cachedCount <= 2.5 * m) {
          if (numZeros != 0) {
            cachedCount = linearCount(m, numZeros);
          }
        } else if (chosenHashBits < 64 && cachedCount > (0.033333 * pow)) {

          // long range bias for 32-bit hashcodes
          if (cachedCount > (1 / 30) * pow) {
            cachedCount = (long) (-pow * Math.log(1.0 - (double) cachedCount / (double) pow));
          }
        }
      }
      invalidateCount = false;
    }
    return cachedCount;
  }

  public void setCount(long count) {
    this.cachedCount = count;
    this.invalidateCount = true;
  }

  private long linearCount(int mVal, long numZeros) {
    return (long) (Math.round(mVal * Math.log(mVal / ((double) numZeros))));
  }

  public double getStandardError() {
    return 1.04 / Math.sqrt(m);
  }

  public HLLDenseRegister getHLLDenseRegister() {
    return denseRegister;
  }

  public HLLSparseRegister getHLLSparseRegister() {
    return sparseRegister;
  }

  public void setHLLSparseRegister(int[] reg) {
    for (int i : reg) {
      int key = i >>> Q_PRIME_VALUE;
      byte value = (byte) (i & 0x3f);
      sparseRegister.set(key, value);
    }
  }

  public void setHLLDenseRegister(byte[] reg) {
    int i = 0;
    for (byte b : reg) {
      denseRegister.set(i, b);
      i++;
    }
  }

  public void merge(HyperLogLog hll) {
    if (p != hll.p || chosenHashBits != hll.chosenHashBits) {
      throw new IllegalArgumentException(
          "HyperLogLog cannot be merged as either p or hashbits are different. Current: "
              + toString() + " Provided: " + hll.toString());
    }

    EncodingType otherEncoding = hll.getEncoding();

    if (encoding.equals(EncodingType.SPARSE) && otherEncoding.equals(EncodingType.SPARSE)) {
      sparseRegister.merge(hll.getHLLSparseRegister());
      // if after merge the sparse switching threshold is exceeded then change to dense encoding
      if (sparseRegister.getSize() > encodingSwitchThreshold) {
        encoding = EncodingType.DENSE;
        denseRegister = HyperLogLogUtils.sparseToDenseRegister(sparseRegister);
        sparseRegister = null;
      }
    } else if (encoding.equals(EncodingType.DENSE) && otherEncoding.equals(EncodingType.DENSE)) {
      denseRegister.merge(hll.getHLLDenseRegister());
    } else if (encoding.equals(EncodingType.SPARSE) && otherEncoding.equals(EncodingType.DENSE)) {
      denseRegister = HyperLogLogUtils.sparseToDenseRegister(sparseRegister);
      denseRegister.merge(hll.getHLLDenseRegister());
      sparseRegister = null;
      encoding = EncodingType.DENSE;
    } else if (encoding.equals(EncodingType.DENSE) && otherEncoding.equals(EncodingType.SPARSE)) {
      HLLDenseRegister otherDenseRegister = HyperLogLogUtils.sparseToDenseRegister(hll
          .getHLLSparseRegister());
      denseRegister.merge(otherDenseRegister);
    }

    invalidateCount = true;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Encoding: ");
    sb.append(encoding);
    sb.append(", p : ");
    sb.append(p);
    sb.append(", chosenHashBits: ");
    sb.append(chosenHashBits);
    sb.append(", estimatedCardinality: ");
    sb.append(count());
    return sb.toString();
  }

  public String toStringExtended() {
    if (encoding.equals(EncodingType.DENSE)) {
      return toString() + ", " + denseRegister.toExtendedString();
    } else if (encoding.equals(EncodingType.SPARSE)) {
      return toString() + ", " + sparseRegister.toExtendedString();
    }

    return toString();
  }

  public int getNumRegisterIndexBits() {
    return p;
  }

  public int getNumHashBits() {
    return chosenHashBits;
  }

  public EncodingType getEncoding() {
    return encoding;
  }

  public void setEncoding(EncodingType encoding) {
    this.encoding = encoding;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof HyperLogLog)) {
      return false;
    }

    HyperLogLog other = (HyperLogLog) obj;
    long count = count();
    long otherCount = other.count();
    boolean result = p == other.p && chosenHashBits == other.chosenHashBits
        && encoding.equals(other.encoding) && count == otherCount;
    if (encoding.equals(EncodingType.DENSE)) {
      result = result && denseRegister.equals(other.getHLLDenseRegister());
    }

    if (encoding.equals(EncodingType.SPARSE)) {
      result = result && sparseRegister.equals(other.getHLLSparseRegister());
    }
    return result;
  }

  @Override
  public int hashCode() {
    int hashcode = 0;
    hashcode += 31 * p;
    hashcode += 31 * chosenHashBits;
    hashcode += encoding.hashCode();
    hashcode += 31 * count();
    if (encoding.equals(EncodingType.DENSE)) {
      hashcode += 31 * denseRegister.hashCode();
    }

    if (encoding.equals(EncodingType.SPARSE)) {
      hashcode += 31 * sparseRegister.hashCode();
    }
    return hashcode;
  }
}
