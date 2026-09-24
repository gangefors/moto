// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! Store support for importing sections ([`crate::exchange`]).

use super::{Store, db_err, insert_section};
use crate::CoreError;
use crate::section::{NewSection, Status};

impl Store {
    /// Applies an import in one transaction: deletes the sections in
    /// `remove` (shorter ones an imported section covers) and adds `add`,
    /// flagged `needs_rematch` until they are fitted to the current region.
    /// Returns the new sections' ids. All or nothing.
    pub fn apply_import(
        &mut self,
        add: &[NewSection],
        remove: &[i64],
        now: i64,
    ) -> Result<Vec<i64>, CoreError> {
        for s in add {
            s.validate()?;
        }
        let tx = self.conn.transaction().map_err(db_err)?;
        for id in remove {
            tx.execute("DELETE FROM sections WHERE id = ?1", [id])
                .map_err(db_err)?;
        }
        let mut ids = Vec::with_capacity(add.len());
        for s in add {
            ids.push(insert_section(&tx, s, now, Status::NeedsRematch)?);
        }
        tx.commit().map_err(db_err)?;
        Ok(ids)
    }
}
