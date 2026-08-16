//! The bridge between the Kotlin PIN screen and the async pairing task.
//!
//! Kotlin calls `SecurityBridge.verifyPin` from a worker thread and expects a boolean.
//! The answer is not knowable at call time: the phone can only tell whether the PIN was
//! right once the PC replies with `SHA256(psk || "auth")` derived from the *same* PIN.
//! So the submission crosses into the tokio runtime, and the calling thread parks on a
//! std channel until the pairing task publishes the verdict (or a timeout fires, so a
//! wedged connection can never hang the caller forever).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{sync_channel, RecvTimeoutError};
use std::time::Duration;

use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};
use tokio::sync::Mutex as AsyncMutex;

/// Upper bound on how long `verifyPin` parks its caller. The pairing task normally
/// answers within a round trip; this only matters if the PC vanishes mid-handshake.
const VERDICT_TIMEOUT: Duration = Duration::from_secs(20);

pub struct PinSubmission {
    pin: String,
    verdict: std::sync::mpsc::SyncSender<bool>,
}

impl PinSubmission {
    pub fn pin(&self) -> &str {
        &self.pin
    }

    /// Publishes the verdict to the parked JNI caller.
    ///
    /// `try_send` rather than `send`: the channel has a slot reserved for exactly this
    /// message, and if the caller already timed out and dropped its receiver there is
    /// nothing to do but move on.
    pub fn answer(self, accepted: bool) {
        let _ = self.verdict.try_send(accepted);
    }
}

pub struct PinChannel {
    tx: UnboundedSender<PinSubmission>,
    rx: AsyncMutex<UnboundedReceiver<PinSubmission>>,
    awaiting: AtomicBool,
}

impl PinChannel {
    pub fn new() -> Self {
        let (tx, rx) = unbounded_channel();
        Self { tx, rx: AsyncMutex::new(rx), awaiting: AtomicBool::new(false) }
    }

    /// True while a pairing task is parked waiting for the user to type a PIN.
    /// Kotlin polls this to decide whether to show the PIN keypad.
    pub fn is_awaiting(&self) -> bool {
        self.awaiting.load(Ordering::SeqCst)
    }

    /// Called from the JNI thread. Blocks until the pairing task decides, and returns
    /// false if no pairing is in progress or the verdict does not arrive in time.
    pub fn submit_blocking(&self, pin: String) -> bool {
        if !self.is_awaiting() {
            crate::log_w!("PIN submitted while no pairing is in progress");
            return false;
        }

        let (verdict_tx, verdict_rx) = sync_channel(1);
        if self.tx.send(PinSubmission { pin, verdict: verdict_tx }).is_err() {
            crate::log_e!("pairing channel is closed; PIN cannot be delivered");
            return false;
        }

        match verdict_rx.recv_timeout(VERDICT_TIMEOUT) {
            Ok(accepted) => accepted,
            Err(RecvTimeoutError::Timeout) => {
                crate::log_w!("timed out waiting for the pairing task to verify the PIN");
                false
            }
            Err(RecvTimeoutError::Disconnected) => {
                crate::log_w!("pairing task dropped before verifying the PIN");
                false
            }
        }
    }

    /// Waits for the next PIN, at most until `deadline` elapses.
    ///
    /// Any submissions that arrived while nobody was waiting are stale — they belong to
    /// an abandoned handshake — so they are rejected before the wait begins rather than
    /// being applied to this one.
    pub async fn next_pin(&self, deadline: Duration) -> Option<PinSubmission> {
        let mut rx = self.rx.lock().await;

        while let Ok(stale) = rx.try_recv() {
            crate::log_w!("discarding a PIN submitted outside the current handshake");
            stale.answer(false);
        }

        self.awaiting.store(true, Ordering::SeqCst);
        let result = tokio::time::timeout(deadline, rx.recv()).await;
        self.awaiting.store(false, Ordering::SeqCst);

        match result {
            Ok(Some(submission)) => Some(submission),
            Ok(None) => None,
            Err(_) => {
                crate::log_w!("pairing timed out waiting for a PIN");
                None
            }
        }
    }

    /// Clears the "waiting for a PIN" flag and rejects anything still queued. Called when
    /// a pairing connection ends for any reason.
    pub async fn cancel(&self) {
        self.awaiting.store(false, Ordering::SeqCst);
        if let Ok(mut rx) = self.rx.try_lock() {
            while let Ok(stale) = rx.try_recv() {
                stale.answer(false);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn submitting_without_a_handshake_is_rejected_immediately() {
        let channel = PinChannel::new();
        assert!(!channel.is_awaiting());
        assert!(!channel.submit_blocking("123456".into()));
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_submitted_pin_reaches_the_waiter_and_the_verdict_returns() {
        let channel = Arc::new(PinChannel::new());

        let submitter = channel.clone();
        let joiner = std::thread::spawn(move || {
            // Wait for the async side to declare itself ready.
            for _ in 0..200 {
                if submitter.is_awaiting() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            submitter.submit_blocking("424242".into())
        });

        let submission = channel.next_pin(Duration::from_secs(5)).await.expect("pin");
        assert_eq!(submission.pin(), "424242");
        submission.answer(true);

        assert!(joiner.join().expect("thread"));
        assert!(!channel.is_awaiting());
    }

    #[tokio::test]
    async fn waiting_times_out_when_no_pin_arrives() {
        let channel = PinChannel::new();
        assert!(channel.next_pin(Duration::from_millis(50)).await.is_none());
        assert!(!channel.is_awaiting());
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_rejected_pin_returns_false_to_the_caller() {
        let channel = Arc::new(PinChannel::new());

        let submitter = channel.clone();
        let joiner = std::thread::spawn(move || {
            for _ in 0..200 {
                if submitter.is_awaiting() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            submitter.submit_blocking("000000".into())
        });

        let submission = channel.next_pin(Duration::from_secs(5)).await.expect("pin");
        submission.answer(false);
        assert!(!joiner.join().expect("thread"));
    }
}
