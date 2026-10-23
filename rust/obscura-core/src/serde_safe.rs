#[derive(serde::Deserialize, Debug, Clone)]
#[serde(untagged)]
pub enum TryParse<T> {
    Valid(T),
    Invalid(serde_json::Value),
}

pub fn deserialize<'de, T: Default + serde::Deserialize<'de>, D: serde::Deserializer<'de>>(deserializer: D) -> Result<T, D::Error> {
    Ok(match serde::Deserialize::deserialize(deserializer)? {
        TryParse::Valid(v) => v,
        TryParse::Invalid(json) => {
            // Only the shape: the value can be an account number, an auth token or a key.
            tracing::error!(
                message_id = "1iEOjvq2",
                field_type = std::any::type_name::<T>(),
                json_type = json_type(&json),
                "Deserialization invalid, using default"
            );
            Default::default()
        }
    })
}

fn json_type(value: &serde_json::Value) -> &'static str {
    match value {
        serde_json::Value::Null => "null",
        serde_json::Value::Bool(_) => "bool",
        serde_json::Value::Number(_) => "number",
        serde_json::Value::String(_) => "string",
        serde_json::Value::Array(_) => "array",
        serde_json::Value::Object(_) => "object",
    }
}
