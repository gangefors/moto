// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Stefan Gangefors

//! A backup's `settings.json`: the app's settings as typed key and value
//! pairs, in a versioned JSON object (`{"schema": 1, "settings": {...}}`).
//! The core only checks their shape (simple keys; booleans, whole
//! numbers or short text); which keys mean what is the app's to check.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::CoreError;

/// The settings schema this build writes and reads.
pub const SETTINGS_SCHEMA: u32 = 1;
/// Most settings in a backup.
pub const MAX_SETTINGS: usize = 256;
/// Longest key and longest text value.
pub const MAX_KEY_CHARS: usize = 64;
pub const MAX_TEXT_CHARS: usize = 256;

/// One setting's value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SettingValue {
    Bool(bool),
    Int(i64),
    Text(String),
}

/// One setting.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Setting {
    pub key: String,
    pub value: SettingValue,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct File {
    schema: u32,
    settings: Map<String, Value>,
}

fn bad(why: &str) -> CoreError {
    CoreError::InvalidArgument(format!("settings: {why}"))
}

fn valid_key(key: &str) -> bool {
    !key.is_empty()
        && key.len() <= MAX_KEY_CHARS
        && key
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
}

fn valid_text(text: &str) -> bool {
    text.chars().count() <= MAX_TEXT_CHARS && !text.chars().any(char::is_control)
}

fn check(settings: &[Setting]) -> Result<(), CoreError> {
    if settings.len() > MAX_SETTINGS {
        return Err(bad("too many"));
    }
    let mut keys = std::collections::HashSet::new();
    for s in settings {
        if !valid_key(&s.key) || !keys.insert(s.key.as_str()) {
            return Err(bad(&format!("invalid or repeated key {:?}", s.key)));
        }
        if let SettingValue::Text(t) = &s.value
            && !valid_text(t)
        {
            return Err(bad(&format!("invalid text for {}", s.key)));
        }
    }
    Ok(())
}

/// `settings` as the file's JSON.
pub(crate) fn to_json(settings: &[Setting]) -> Result<Vec<u8>, CoreError> {
    check(settings)?;
    let map = settings
        .iter()
        .map(|s| {
            let v = match &s.value {
                SettingValue::Bool(b) => Value::Bool(*b),
                SettingValue::Int(i) => Value::from(*i),
                SettingValue::Text(t) => Value::String(t.clone()),
            };
            (s.key.clone(), v)
        })
        .collect();
    serde_json::to_vec_pretty(&File {
        schema: SETTINGS_SCHEMA,
        settings: map,
    })
    .map_err(|e| bad(&e.to_string()))
}

/// The settings in a file's JSON, checked: a known schema, simple keys,
/// and only booleans, whole numbers and short text.
pub(crate) fn from_json(json: &[u8]) -> Result<Vec<Setting>, CoreError> {
    let file: File = serde_json::from_slice(json).map_err(|e| bad(&e.to_string()))?;
    if file.schema != SETTINGS_SCHEMA {
        return Err(bad("unknown schema"));
    }
    let settings = file
        .settings
        .into_iter()
        .map(|(key, v)| {
            let value = match v {
                Value::Bool(b) => SettingValue::Bool(b),
                Value::Number(n) => {
                    SettingValue::Int(n.as_i64().ok_or_else(|| bad("not a whole number"))?)
                }
                Value::String(s) => SettingValue::Text(s),
                _ => return Err(bad("a value is not a boolean, number or text")),
            };
            Ok(Setting { key, value })
        })
        .collect::<Result<Vec<_>, _>>()?;
    check(&settings)?;
    Ok(settings)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(key: &str, value: SettingValue) -> Setting {
        Setting {
            key: key.into(),
            value,
        }
    }

    #[test]
    fn settings_round_trip() {
        let all = vec![
            s("dark_theme", SettingValue::Text("dark".into())),
            s("ride_zoom_step", SettingValue::Int(3)),
            s("ride_turn_map", SettingValue::Bool(false)),
        ];
        let mut back = from_json(&to_json(&all).unwrap()).unwrap();
        back.sort_by(|a, b| a.key.cmp(&b.key));
        let mut want = all;
        want.sort_by(|a, b| a.key.cmp(&b.key));
        assert_eq!(back, want);
    }

    #[test]
    fn odd_settings_are_refused() {
        for json in [
            r#"{"schema":2,"settings":{}}"#,
            r#"{"schema":1,"settings":{},"x":1}"#,
            r#"{"schema":1,"settings":{"a":1.5}}"#,
            r#"{"schema":1,"settings":{"a":null}}"#,
            r#"{"schema":1,"settings":{"a":[1]}}"#,
            r#"{"schema":1,"settings":{"a":{"b":1}}}"#,
            r#"{"schema":1,"settings":{"A":1}}"#,
            r#"{"schema":1,"settings":{"../x":1}}"#,
            r#"{"schema":1,"settings":{"":1}}"#,
            r#"{"schema":1,"settings":{"a":"line\nbreak"}}"#,
            r#"{"schema":1,"settings":{"a":18446744073709551615}}"#,
            r#"[1]"#,
            r#"not json"#,
        ] {
            assert!(from_json(json.as_bytes()).is_err(), "{json}");
        }
        let long = format!(
            r#"{{"schema":1,"settings":{{"a":"{}"}}}}"#,
            "x".repeat(MAX_TEXT_CHARS + 1)
        );
        assert!(from_json(long.as_bytes()).is_err());
        let many: Vec<_> = (0..=MAX_SETTINGS)
            .map(|i| s(&format!("k{i}"), SettingValue::Int(1)))
            .collect();
        assert!(to_json(&many).is_err());
        let twice = [s("a", SettingValue::Int(1)), s("a", SettingValue::Int(2))];
        assert!(to_json(&twice).is_err());
    }
}
