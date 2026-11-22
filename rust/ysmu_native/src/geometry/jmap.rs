//! Java `HashMap<String, _>` iteration-order emulation.
//!
//! Why this exists: `RawGeometryTree.parseHierarchy` stores the hierarchy in
//! `HashMap<String, RawBoneGroup>` (`RawGeometryTree.java:16`, `RawBoneGroup.java:9`) and
//! `GeoBuilder.constructGeoModel` walks it with `values()`
//! (`GeoBuilder.java:45`, `:117`). Java's `HashMap` iteration order is a function of `String.hashCode`,
//! the spread function, the table capacity and insertion order within a bucket - not document order and
//! not insertion order. The port's renderer therefore draws `topLevelBones`/`childBones` in exactly that
//! order, so a faithful native bake has to reproduce it byte for byte; the parity harness compares the
//! whole built-in corpus order-sensitively.
//!
//! The emulation is the Java 8 `HashMap` relevant to this use:
//!
//! * `String.hashCode` over UTF-16 code units, `h ^ (h >>> 16)` spread, bucket `= spread & (cap - 1)`;
//! * table capacity starts at 16 on the first `put` and doubles when `size > capacity * 0.75`;
//! * a `put` of an existing key replaces the value in place (order unchanged); a new key is appended to
//!   the tail of its bucket chain;
//! * `resize` splits every chain into a "low" and a "high" half, preserving the relative order of both;
//! * iteration is bucket index ascending, chain order within a bucket.
//!
//! One case is not emulated: when one bucket grows to nine entries at capacity 64 or more, Java
//! *treeifies* it and the red-black tree order replaces the chain order. That needs a full port of
//! `HashMap.treeify`'s tie-breaking to reproduce, and no realistic set of bone names hashes that badly.
//! The map instead reports [`JMapError::Treeified`] and the bake declines the model, so the Java path
//! runs and produces the authoritative order - a slow path, never a divergent one.

/// Why a `JavaHashMap` could not reproduce Java's structure.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JMapError {
    /// A bucket reached nine entries at capacity >= 64, where Java would treeify it. See the module docs.
    Treeified,
}

/// Java `String.hashCode`: `h = 31 * h + unit` over UTF-16 code units, wrapping at 32 bits.
pub fn java_string_hash(value: &str) -> u32 {
    let mut hash: i32 = 0;
    for unit in value.encode_utf16() {
        hash = hash.wrapping_mul(31).wrapping_add(unit as i32);
    }
    hash as u32
}

fn spread(hash: u32) -> u32 {
    // `static final int hash(Object key) { return (h = key.hashCode()) ^ (h >>> 16); }`
    hash ^ (hash >> 16)
}

/// One entry of the emulated map (insertion order, for stable arena references).
#[derive(Debug, Clone)]
struct Entry {
    key: Option<String>,
    value: usize,
    /// The spread hash, i.e. `h ^ (h >>> 16)`; `resize` and bucket indexes use it.
    hash: u32,
}

const DEFAULT_INITIAL_CAPACITY: usize = 16;
/// `HashMap.TREEIFY_THRESHOLD`.
const TREEIFY_THRESHOLD: usize = 8;
/// `HashMap.MIN_TREEIFY_CAPACITY`.
const MIN_TREEIFY_CAPACITY: usize = 64;

/// A `HashMap<Option<String>, usize>` with Java's iteration order. `None` is Java's `null` key (bone
/// names are nullable in the POJO, and `HashMap` accepts exactly one null key).
#[derive(Debug, Default)]
pub struct JavaHashMap {
    entries: Vec<Entry>,
    /// Bucket index -> entry indexes, chain order.
    buckets: Vec<Vec<usize>>,
    size: usize,
}

impl JavaHashMap {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.size
    }

    pub fn is_empty(&self) -> bool {
        self.size == 0
    }

    /// Java `HashMap.put`: replaces in place or appends to the bucket tail, resizing as Java does.
    pub fn put(&mut self, key: Option<String>, value: usize) -> Result<(), JMapError> {
        if self.buckets.is_empty() {
            // First `put` allocates the table through `resize()` with the default initial capacity.
            self.resize(DEFAULT_INITIAL_CAPACITY);
        }
        let hash = match &key {
            Some(text) => spread(java_string_hash(text)),
            // `HashMap.hash(null)` is 0.
            None => 0,
        };
        let bucket = (hash as usize) & (self.buckets.len() - 1);
        for &entry in &self.buckets[bucket] {
            if self.entries[entry].key.as_deref() == key.as_deref() {
                self.entries[entry].value = value;
                return Ok(());
            }
        }
        let entry = self.entries.len();
        self.entries.push(Entry { key, value, hash });
        self.buckets[bucket].push(entry);
        self.size += 1;
        // `putVal` order: the treeify check happens while linking the new node, the load-factor resize
        // after it. Appending the ninth node of a chain (`binCount >= TREEIFY_THRESHOLD - 1`) calls
        // `treeifyBin`, which resizes below capacity 64 and treeifies at 64 or more.
        if self.buckets[bucket].len() > TREEIFY_THRESHOLD {
            if self.buckets.len() < MIN_TREEIFY_CAPACITY {
                self.resize(self.buckets.len() * 2);
            } else {
                return Err(JMapError::Treeified);
            }
        }
        // Java checks the load factor after insertion; `threshold` is `capacity * 3 / 4`.
        if self.size > self.buckets.len() * 3 / 4 {
            self.resize(self.buckets.len() * 2);
        }
        Ok(())
    }

    /// Java `HashMap.resize`: doubles the table and splits each chain into its low/high halves,
    /// preserving order inside each half (`resize` in `HashMap.java`, Java 8).
    fn resize(&mut self, new_capacity: usize) {
        let old_capacity = self.buckets.len();
        let mut new_buckets: Vec<Vec<usize>> = vec![Vec::new(); new_capacity];
        if old_capacity == 0 {
            self.buckets = new_buckets;
            return;
        }
        for (bucket_index, chain) in self.buckets.iter().enumerate() {
            // `HashMap.resize` leaves the single-entry chains in place with the same index.
            if chain.is_empty() {
                continue;
            }
            let mut low: Vec<usize> = Vec::new();
            let mut high: Vec<usize> = Vec::new();
            for &entry in chain {
                // Java tests `e.hash & oldCap`: entries whose bit is clear keep the index.
                if self.entries[entry].hash as usize & old_capacity == 0 {
                    low.push(entry);
                } else {
                    high.push(entry);
                }
            }
            new_buckets[bucket_index] = low;
            new_buckets[bucket_index + old_capacity] = high;
        }
        self.buckets = new_buckets;
    }

    /// Java `HashMap` iteration: values in bucket-then-chain order.
    pub fn values(&self) -> Vec<usize> {
        let mut result = Vec::with_capacity(self.size);
        for chain in &self.buckets {
            for &entry in chain {
                result.push(self.entries[entry].value);
            }
        }
        result
    }

    /// Key/value pairs in Java iteration order (`HashMap.entrySet()` order).
    pub fn entries_in_order(&self) -> Vec<(Option<&str>, usize)> {
        let mut result = Vec::with_capacity(self.size);
        for chain in &self.buckets {
            for &entry in chain {
                result.push((self.entries[entry].key.as_deref(), self.entries[entry].value));
            }
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::{java_string_hash, JavaHashMap};

    fn map_of(keys: &[&str]) -> JavaHashMap {
        let mut map = JavaHashMap::new();
        for (index, key) in keys.iter().enumerate() {
            map.put(Some((*key).to_string()), index).expect("no treeify");
        }
        map
    }

    fn order(map: &JavaHashMap) -> Vec<usize> {
        map.values()
    }

    #[test]
    fn hash_matches_the_java_jdk() {
        // Values computed by the JDK's documented `String.hashCode` algorithm (h = 31*h + unit).
        assert_eq!(java_string_hash("abc"), 96354);
        assert_eq!(java_string_hash("body"), 3029410);
        assert_eq!(java_string_hash("Aa"), 2112);
        assert_eq!(java_string_hash("BB"), 2112);
        assert_eq!(java_string_hash(""), 0);
        // Non-ASCII: code units, not bytes; "头" is U+5934.
        assert_eq!(java_string_hash("\u{5934}"), 0x5934);
    }

    #[test]
    fn iteration_order_is_bucket_then_chain() {
        // h("a")=97 -> bucket 1, h("b")=98 -> bucket 2, h("c")=99 -> bucket 3: insertion order happens
        // to agree, so use the classic Aa/BB collision pair for the interesting case.
        assert_eq!(order(&map_of(&["a", "b", "c"])), vec![0, 1, 2]);
        // Aa and BB share a hash (2112 & 15 = 0): chain order is insertion order, so the values come back
        // in insertion order regardless of which key was inserted first.
        assert_eq!(order(&map_of(&["Aa", "BB"])), vec![0, 1]);
        assert_eq!(order(&map_of(&["BB", "Aa"])), vec![0, 1]);
    }

    #[test]
    fn replacement_keeps_order_and_one_entry() {
        let mut map = JavaHashMap::new();
        map.put(Some("head".into()), 1).unwrap();
        map.put(Some("body".into()), 2).unwrap();
        map.put(Some("head".into()), 3).unwrap();
        assert_eq!(map.len(), 2);
        assert_eq!(map.entries_in_order(), vec![(Some("head"), 3), (Some("body"), 2)]);
    }

    #[test]
    fn null_key_is_allowed_and_hashes_like_java() {
        let mut map = JavaHashMap::new();
        map.put(None, 7).unwrap();
        map.put(Some("x".into()), 8).unwrap();
        assert_eq!(map.len(), 2);
        assert_eq!(map.values().len(), 2);
        assert_eq!(map.entries_in_order().iter().filter(|(key, _)| key.is_none()).count(), 1);
    }

    #[test]
    fn resize_splits_chains_preserving_order() {
        // 13 keys push capacity 16 -> 32 through the load factor.
        let keys: Vec<String> = (0..13).map(|index| format!("bone_{index}")).collect();
        let mut map = JavaHashMap::new();
        for (index, key) in keys.iter().enumerate() {
            map.put(Some(key.clone()), index).unwrap();
        }
        assert_eq!(map.len(), 13);
        let mut seen: Vec<usize> = order(&map);
        seen.sort_unstable();
        assert_eq!(seen, (0..13).collect::<Vec<_>>());
    }
}
