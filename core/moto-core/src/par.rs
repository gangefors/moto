// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Running independent searches side by side (the rider: everything that can
//! run in parallel should). Scoped threads from the standard library, no
//! thread pool: the searches are long (tens of milliseconds and up), so
//! starting a few threads per request costs nothing measurable.

use std::cell::Cell;
use std::sync::atomic::{AtomicUsize, Ordering};

thread_local! {
    /// Set on the threads [`map`] starts: a `map` inside one runs its items
    /// one after the other, so a request never uses more than
    /// [`MAX_THREADS`] threads however deep the calls go.
    static WORKER: Cell<bool> = const { Cell::new(false) };
}

/// Most threads one request uses: the phone's cores, bounded so that
/// several searches' working memory (tens of MB each) stays small.
pub const MAX_THREADS: usize = 4;

/// `f` applied to every item, on up to [`MAX_THREADS`] threads, the
/// results in the items' order, so the outcome is the same as one after
/// the other. A thread that panics panics the caller, as a plain loop
/// would. Inside another `map`, one after the other.
pub(crate) fn map<T: Sync, R: Send>(items: &[T], f: impl Fn(&T) -> R + Sync) -> Vec<R> {
    let threads = std::thread::available_parallelism()
        .map_or(1, |n| n.get())
        .min(MAX_THREADS)
        .min(items.len());
    if threads <= 1 || WORKER.with(Cell::get) {
        return items.iter().map(f).collect();
    }
    let next = AtomicUsize::new(0);
    let mut done: Vec<(usize, R)> = std::thread::scope(|s| {
        let workers: Vec<_> = (0..threads)
            .map(|_| {
                s.spawn(|| {
                    WORKER.with(|w| w.set(true));
                    let mut out = Vec::new();
                    loop {
                        let i = next.fetch_add(1, Ordering::Relaxed);
                        let Some(item) = items.get(i) else {
                            break out;
                        };
                        out.push((i, f(item)));
                    }
                })
            })
            .collect();
        workers
            .into_iter()
            .flat_map(|w| w.join().unwrap_or_else(|p| std::panic::resume_unwind(p)))
            .collect()
    });
    done.sort_unstable_by_key(|(i, _)| *i);
    done.into_iter().map(|(_, r)| r).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn results_keep_the_items_order() {
        let items: Vec<u64> = (0..100).collect();
        let out = map(&items, |&x| {
            // Uneven work, so threads finish out of order.
            (0..(x % 7) * 10_000).fold(x, |a, b| a.wrapping_add(b % 3))
        });
        let one_by_one: Vec<u64> = items
            .iter()
            .map(|&x| (0..(x % 7) * 10_000).fold(x, |a, b| a.wrapping_add(b % 3)))
            .collect();
        assert_eq!(out, one_by_one);
        assert!(map(&[] as &[u8], |&x| x).is_empty());
        assert_eq!(map(&[5u8], |&x| x * 2), [10]);
    }

    #[test]
    fn a_map_inside_a_map_runs_in_its_thread() {
        let outer: Vec<u32> = (0..6).collect();
        let out = map(&outer, |&x| {
            let here = std::thread::current().id();
            let inner: Vec<u32> = (0..5).collect();
            let ids = map(&inner, |_| std::thread::current().id());
            assert!(ids.iter().all(|&id| id == here) || !WORKER.with(Cell::get));
            map(&inner, |&y| x * 10 + y).iter().sum::<u32>()
        });
        assert_eq!(
            out,
            (0..6)
                .map(|x| (0..5).map(|y| x * 10 + y).sum())
                .collect::<Vec<u32>>()
        );
    }

    #[test]
    #[should_panic(expected = "boom")]
    fn a_panic_reaches_the_caller() {
        let items: Vec<u32> = (0..8).collect();
        map(&items, |&x| if x == 5 { panic!("boom") } else { x });
    }
}
