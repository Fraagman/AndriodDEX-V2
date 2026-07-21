use shared_protocol::EncodedVideoFrame;
use std::collections::VecDeque;

/// A simple Jitter Buffer to reorder out-of-order packets and handle timing delays.
/// Critical for Step 3 of Milestone 1.
pub struct JitterBuffer {
    queue: VecDeque<EncodedVideoFrame>,
    max_depth: usize,
}

impl JitterBuffer {
    pub fn new(max_depth: usize) -> Self {
        Self {
            queue: VecDeque::with_capacity(max_depth),
            max_depth,
        }
    }

    /// Pushes a received frame into the jitter buffer, sorting by sequence number if necessary.
    pub fn push(&mut self, frame: EncodedVideoFrame) {
        if self.queue.len() >= self.max_depth {
            // Drop oldest frame to catch up (or handle more intelligently in the future)
            self.queue.pop_front();
        }

        // Insert maintaining sequence order
        let insert_idx = self.queue
            .iter()
            .rposition(|f| f.sequence_number < frame.sequence_number)
            .map(|i| i + 1)
            .unwrap_or(0);

        self.queue.insert(insert_idx, frame);
    }

    /// Pops the next frame ready for decoding, if any.
    pub fn pop(&mut self) -> Option<EncodedVideoFrame> {
        self.queue.pop_front()
    }

    pub fn depth(&self) -> usize {
        self.queue.len()
    }
}
