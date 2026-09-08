//! The bridge between the user confirmation screen and the async pairing task.
//!
//! Under Protocol v2, the phone no longer receives a PIN. Instead, it computes the 6-digit
//! SAS itself, displays it to the user, and waits for the user to tap "Codes match" or
//! "They're different".
//! Kotlin calls `SecurityBridge.confirmPairing(matched)` from a worker thread and expects a boolean.
//! The submission crosses into the tokio runtime, and the calling thread parks on a
//! std channel until the pairing task publishes the verdict (or a timeout fires, so a
//! wedged connection can never hang the caller forever).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{sync_channel, RecvTimeoutError, SyncSender};
use std::sync::Mutex;
use std::time::Duration;

use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};
use tokio::sync::Mutex as AsyncMutex;

/// Upper bound on how long confirmation submission parks its caller.
const VERDICT_TIMEOUT: Duration = Duration::from_secs(20);

pub struct ConfirmationSubmission {
    matched: bool,
    verdict: SyncSender<bool>,
}

impl ConfirmationSubmission {
    pub fn matched(&self) -> bool {
        self.matched
    }

    /// Publishes the verdict to the parked JNI caller.
    pub fn answer(self, accepted: bool) {
        let _ = self.verdict.try_send(accepted);
    }
}

pub struct ConfirmationChannel {
    tx: UnboundedSender<ConfirmationSubmission>,
    rx: AsyncMutex<UnboundedReceiver<ConfirmationSubmission>>,
    awaiting: AtomicBool,
    pending_sas: Mutex<Option<String>>,
}

impl ConfirmationChannel {
    pub fn new() -> Self {
        let (tx, rx) = unbounded_channel();
        Self {
            tx,
            rx: AsyncMutex::new(rx),
            awaiting: AtomicBool::new(false),
            pending_sas: Mutex::new(None),
        }
    }

    /// True while a pairing task is parked waiting for the user to confirm or reject the SAS.
    pub fn is_awaiting(&self) -> bool {
        self.awaiting.load(Ordering::SeqCst)
    }

    /// The 6-digit SAS code currently pending confirmation, if any.
    pub fn get_pending_sas(&self) -> Option<String> {
        match self.pending_sas.lock() {
            Ok(g) => g.clone(),
            Err(p) => p.into_inner().clone(),
        }
    }

    fn set_pending_sas(&self, sas: Option<String>) {
        match self.pending_sas.lock() {
            Ok(mut g) => *g = sas,
            Err(p) => *p.into_inner() = sas,
        }
    }

    /// Called from the JNI thread. Blocks until the pairing task decides, and returns
    /// false if no pairing is in progress or the verdict does not arrive in time.
    pub fn submit_blocking(&self, matched: bool) -> bool {
        if !self.is_awaiting() {
            crate::log_w!("confirmation submitted while no pairing is in progress");
            return false;
        }

        let (verdict_tx, verdict_rx) = sync_channel(1);
        if self.tx.send(ConfirmationSubmission { matched, verdict: verdict_tx }).is_err() {
            crate::log_e!("pairing channel is closed; confirmation cannot be delivered");
            return false;
        }

        match verdict_rx.recv_timeout(VERDICT_TIMEOUT) {
            Ok(accepted) => accepted,
            Err(RecvTimeoutError::Timeout) => {
                crate::log_w!("timed out waiting for pairing task to process confirmation");
                false
            }
            Err(RecvTimeoutError::Disconnected) => {
                crate::log_w!("pairing task dropped before processing confirmation");
                false
            }
        }
    }

    /// Waits for the next user confirmation, at most until `deadline` elapses.
    pub async fn wait_for_confirmation(&self, sas: String, deadline: Duration) -> Option<ConfirmationSubmission> {
        let mut rx = self.rx.lock().await;

        while let Ok(stale) = rx.try_recv() {
            crate::log_w!("discarding a confirmation submitted outside the current handshake");
            stale.answer(false);
        }

        self.set_pending_sas(Some(sas));
        self.awaiting.store(true, Ordering::SeqCst);

        let result = tokio::time::timeout(deadline, rx.recv()).await;

        self.awaiting.store(false, Ordering::SeqCst);
        self.set_pending_sas(None);

        match result {
            Ok(Some(submission)) => Some(submission),
            Ok(None) => None,
            Err(_) => {
                crate::log_w!("pairing timed out waiting for user confirmation");
                None
            }
        }
    }

    /// Clears the waiting flag, resets pending SAS, and rejects anything queued.
    pub async fn cancel(&self) {
        self.awaiting.store(false, Ordering::SeqCst);
        self.set_pending_sas(None);
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
        let channel = ConfirmationChannel::new();
        assert!(!channel.is_awaiting());
        assert!(channel.get_pending_sas().is_none());
        assert!(!channel.submit_blocking(true));
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_submitted_confirmation_reaches_the_waiter_and_the_verdict_returns() {
        let channel = Arc::new(ConfirmationChannel::new());

        let submitter = channel.clone();
        let joiner = std::thread::spawn(move || {
            for _ in 0..200 {
                if submitter.is_awaiting() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            assert_eq!(submitter.get_pending_sas().as_deref(), Some("040666"));
            submitter.submit_blocking(true)
        });

        let submission = channel
            .wait_for_confirmation("040666".to_string(), Duration::from_secs(5))
            .await
            .expect("submission");
        assert!(submission.matched());
        submission.answer(true);

        assert!(joiner.join().expect("thread"));
        assert!(!channel.is_awaiting());
        assert!(channel.get_pending_sas().is_none());
    }

    #[tokio::test]
    async fn waiting_times_out_when_no_confirmation_arrives() {
        let channel = ConfirmationChannel::new();
        assert!(channel
            .wait_for_confirmation("123456".to_string(), Duration::from_millis(50))
            .await
            .is_none());
        assert!(!channel.is_awaiting());
        assert!(channel.get_pending_sas().is_none());
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn a_rejected_confirmation_returns_false_to_the_caller() {
        let channel = Arc::new(ConfirmationChannel::new());

        let submitter = channel.clone();
        let joiner = std::thread::spawn(move || {
            for _ in 0..200 {
                if submitter.is_awaiting() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            submitter.submit_blocking(false)
        });

        let submission = channel
            .wait_for_confirmation("654321".to_string(), Duration::from_secs(5))
            .await
            .expect("submission");
        assert!(!submission.matched());
        submission.answer(false);

        assert!(!joiner.join().expect("thread"));
    }

    #[tokio::test]
    async fn cancel_clears_pending_sas_and_awaiting_state() {
        let channel = ConfirmationChannel::new();
        channel.set_pending_sas(Some("999888".to_string()));
        channel.awaiting.store(true, Ordering::SeqCst);
        assert!(channel.is_awaiting());
        assert_eq!(channel.get_pending_sas().as_deref(), Some("999888"));

        channel.cancel().await;

        assert!(!channel.is_awaiting());
        assert!(channel.get_pending_sas().is_none());
    }
}
