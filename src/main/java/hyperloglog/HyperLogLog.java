package hyperloglog;

import java.nio.charset.Charset;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

public class HyperLogLog {

  // number of registers
  private int m;
  private float alpha;

  // number of bits to address registers
  private int p;

  // 8-bit registers.
  // TODO: This can further be space optimized using 6 bit registers as longest
  // run for long value (hashcode) can be maximum of 64.
  // Using p = 14,
  // space required for 8-bit registers = (2 ^ 14) * 8 = 16KB
  // space required for 8-bit registers = (2 ^ 14) * 6 = 12KB
  private byte[] register;

  // Good fast hash function suggested by Guava hashing for the specified bits
  // Default is MurmurHash3_128
  private HashFunction hf;
  private HashCode hc;

  // LSB p bits of hashcode
  private int registerIdx;

  // MSB (64 - p) bits of hashcode
  private long w;

  // longest run of zeroes
  private int lr;

  private long numElems;

  // counts are cached to avoid complex computation. If register value is updated
  // the count will be computed again.
  private long cachedCount;
  private boolean countInvalidate;

  /**
   * By default, HyperLogLog uses 14 LSB bits of hashcode as register index and a 64 bit
   * hashfunction (Good hash function for 64bit as suggested by Google Guava library is
   * MurmurHash3_128).
   */
  public HyperLogLog() {
    this(14, 64);
  }

  /**
   * Specify the LSB number of bits in hashcode to be used as register index. Also specify the
   * number bits for hash function. The hash function is chosen by Guava library based on the
   * specified bits.
   * @param p
   *          - number of register bits in the range 4 to 16 (inclusive)
   * @param numBitsHash
   *          - bits for hash function
   */
  public HyperLogLog(int p, int numBitsHash) {
    this.p = p;
    this.m = 1 << p;
    this.register = new byte[m];
    initializeAlpha(numBitsHash);
    this.hf = Hashing.goodFastHash(numBitsHash);
    this.numElems = 0;
    this.cachedCount = -1;
    this.countInvalidate = false;
  }

  // see paper for alpha initialization
  private void initializeAlpha(int numBitsHash) {
    if (numBitsHash <= 16) {
      alpha = 0.673f;
    } else if (numBitsHash <= 32) {
      alpha = 0.697f;
    } else if (numBitsHash <= 64) {
      alpha = 0.709f;
    } else {
      alpha = 0.7213f / (float) (1 + 1.079f / m);
    }
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

  private long getHashCode() {
    long hashcode = 0;
    if (hc.bits() < 64) {
      hashcode = hc.asInt();
    } else {
      hashcode = hc.asLong();
    }
    return hashcode;
  }

  public void add(long hashcode) {
    numElems++;

    // LSB p bits
    registerIdx = (int) (hashcode & (m - 1));

    // MSB 64 - p bits
    w = hashcode >>> p;

    // longest run of zeroes
    lr = findLongestRun(w);

    // update register if the longest run exceeds the previous entry
    int currentVal = register[registerIdx];
    if (lr > currentVal) {
      register[registerIdx] = (byte) lr;
      countInvalidate = true;
    }
  }

  public long count() {

    // compute count only if the register values are updated else return the
    // cached count
    if (countInvalidate || cachedCount < -1) {
      double sum = 0;
      long numZeros = 0;
      for (int i = 0; i < register.length; i++) {
        if (register[i] == 0) {
          numZeros++;
          sum += 1;
        } else {
          sum += Math.pow(2, -register[i]);
        }
      }

      // cardinality estimate from normalized bias corrected harmonic mean on
      // the registers
      cachedCount = (long) (alpha * (Math.pow(m, 2)) * (1d / (double) sum));
      int bits = hc.bits();
      long pow = (long) Math.pow(2, bits);

      // HLL algorithm shows stronger bias for values in (2.5 * m) range.
      // To compensate for this short range bias, linear counting is used for
      // values before this short range. The original paper also says similar
      // bias is seen for long range values due to hash collisions in range >1/30*(2^32)
      // For the default case, we do not have to worry about this long range bias
      // as the paper used 32-bit hashing and we use 64-bit hashing as default.
      // 2^64 values are too high to observe long range bias.
      if (numElems <= 2.5 * m) {
        if (numZeros != 0) {
          cachedCount = linearCount(numZeros);
        }
      } else if (bits < 64 && numElems > (0.033333 * pow)) {

        // long range bias for 32-bit hashcodes
        if (numElems > (1 / 30) * pow) {
          cachedCount = (long) (-pow * Math.log(1.0 - (double) cachedCount / (double) pow));
        }
      }

      countInvalidate = false;
    }
    return cachedCount;
  }

  private long linearCount(long numZeros) {
    return (long) (Math.round(m * Math.log(m / ((double) numZeros))));
  }

  private int findLongestRun(long v) {
    int i = 1;
    while ((v & 1) == 0) {
      v = v >>> 1;
      i++;
    }
    return i;
  }

  public double getStandardError() {
    return 1.04 / Math.sqrt(m);
  }

  public byte[] getRegister() {
    return register;
  }

  public void setRegister(byte[] register) {
    this.register = register;
  }

  public void merge(HyperLogLog hll) {
    byte[] inRegister = hll.getRegister();

    if (register.length != inRegister.length) {
      throw new IllegalArgumentException(
          "The size of register sets of HyperLogLogs to be merged does not match.");
    }

    for (int i = 0; i < inRegister.length; i++) {
      if (inRegister[i] > register[i]) {
        register[i] = inRegister[i];
      }
    }
    countInvalidate = true;
  }
}
