//! A bounded, drop-oldest frame queue.
//!
//! `tokio::sync::mpsc` cannot express this: with `try_send` a full channel rejects the
//! *new* frame, which is the wrong one to lose — the newest frame is the one the viewer
//! actually wants, and keeping a stale backlog is how end-to-end latency grows without
//! ever recovering. Here the producer evicts the oldest queued frame instead, so the
//! queue depth is a hard bound rather than a target.
//!
//! The producer side never blocks and never awaits, which matters because it is called
//! straight from the MediaCodec output callback over JNI.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use tokio::sync::Notify;

pub struct FrameQueue {
    queue: Mutex<VecDeque<Vec<u8>>>,
    notify: Notify,
    capacity: usize,
    dropped: AtomicU64,
}

impl FrameQueue {
    pub fn new(capacity: usize) -> Self {
        // A zero-capacity queue would discard everything; treat it as one slot.
        let capacity = capacity.max(1);
        Self {
            queue: Mutex::new(VecDeque::with_capacity(capacity)),
            notify: Notify::new(),
            capacity,
            dropped: AtomicU64::new(0),
        }
    }

    /// Enqueues `frame`, evicting the oldest frames until the queue fits.
    ///
    /// Returns the number of frames evicted by this call.
    pub fn push(&self, frame: Vec<u8>) -> usize {
        let mut evicted = 0usize;
        {
            // A poisoned lock would mean a producer panicked mid-push. Recovering the
            // guard is correct here: the only invariant is "the deque is a deque".
            let mut q = match self.queue.lock() {
                Ok(q) => q,
                Err(poisoned) => poisoned.into_inner(),
            };
            while q.len() >= self.capacity {
                if q.pop_front().is_none() {
                    break;
                }
                evicted += 1;
            }
            q.push_back(frame);
        }
        if evicted > 0 {
            self.dropped.fetch_add(evicted as u64, Ordering::Relaxed);
        }
        self.notify.notify_one();
        evicted
    }

    /// Waits for the next frame.
    ///
    /// `Notify` stores a permit when nobody is waiting, so a `push` that lands between
    /// the `pop_front` miss below and the `await` still wakes this task.
    pub async fn pop(&self) -> Vec<u8> {
        loop {
            let notified = self.notify.notified();
            if let Some(frame) = self.try_pop() {
                return frame;
            }
            notified.await;
        }
    }

    fn try_pop(&self) -> Option<Vec<u8>> {
        let mut q = match self.queue.lock() {
            Ok(q) => q,
            Err(poisoned) => poisoned.into_inner(),
        };
        q.pop_front()
    }

    /// Discards anything queued. Called when a session starts so a new viewer does not
    /// receive frames captured before it connected.
    pub fn clear(&self) {
        let mut q = match self.queue.lock() {
            Ok(q) => q,
            Err(poisoned) => poisoned.into_inner(),
        };
        q.clear();
    }

    /// Total frames evicted since process start.
    pub fn dropped(&self) -> u64 {
        self.dropped.load(Ordering::Relaxed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn drops_the_oldest_when_full() {
        let q = FrameQueue::new(2);
        assert_eq!(q.push(vec![1]), 0);
        assert_eq!(q.push(vec![2]), 0);
        // Third push evicts frame 1, not frame 3.
        assert_eq!(q.push(vec![3]), 1);
        assert_eq!(q.dropped(), 1);

        assert_eq!(q.try_pop(), Some(vec![2]));
        assert_eq!(q.try_pop(), Some(vec![3]));
        assert_eq!(q.try_pop(), None);
    }

    #[test]
    fn depth_never_exceeds_capacity() {
        let q = FrameQueue::new(2);
        for i in 0..1000u16 {
            q.push(i.to_le_bytes().to_vec());
        }
        assert_eq!(q.queue.lock().expect("lock").len(), 2);
        assert_eq!(q.dropped(), 998);
        // The two survivors are the two newest frames.
        assert_eq!(q.try_pop(), Some(998u16.to_le_bytes().to_vec()));
        assert_eq!(q.try_pop(), Some(999u16.to_le_bytes().to_vec()));
    }

    #[test]
    fn clear_empties_without_counting_drops() {
        let q = FrameQueue::new(4);
        q.push(vec![1]);
        q.push(vec![2]);
        q.clear();
        assert_eq!(q.dropped(), 0);
        assert!(q.try_pop().is_none());
    }

    #[tokio::test]
    async fn pop_wakes_on_a_later_push() {
        let q = Arc::new(FrameQueue::new(2));
        let producer = q.clone();
        tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
            producer.push(vec![7, 7, 7]);
        });
        assert_eq!(q.pop().await, vec![7, 7, 7]);
    }
}
