use std::{
    collections::{HashMap, HashSet},
    num::Saturating,
};

use obscuravpn_api::types::{CityCode, CountryCode, OneExit, OneRelay, RelayPreferredExit};
use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize, Deserialize, PartialEq, Eq, Clone)]
#[serde(rename_all = "camelCase")]
pub enum ExitSelector {
    Any {},
    Exit {
        id: String,
    },
    Country {
        country_code: CountryCode,
    },
    City {
        #[serde(flatten)]
        city_code: CityCode,
    },
}

impl ExitSelector {
    pub fn matches(&self, candidate: &OneExit) -> bool {
        match self {
            ExitSelector::Any {} => true,
            ExitSelector::Exit { id } => candidate.id == *id,
            ExitSelector::Country { country_code } => candidate.city_code.country_code == *country_code,
            ExitSelector::City { city_code } => candidate.city_code == *city_code,
        }
    }
}

impl Default for ExitSelector {
    fn default() -> Self {
        ExitSelector::Any {}
    }
}

#[derive(Debug, Default)]
pub struct ExitSelectionState {
    selected_exit_ids: HashSet<String>,
    selected_datacenters: HashMap<u32, Saturating<u8>>,
    selected_cities: HashMap<CityCode, Saturating<u8>>,
    selected_countries: HashMap<CountryCode, Saturating<u8>>,
}

impl ExitSelectionState {
    pub fn select_next_exit<'a>(&mut self, selector: &ExitSelector, exits: &'a [OneExit], relay: &OneRelay) -> Option<&'a OneExit> {
        let mut selected = self.best_exit(selector, exits, relay);
        if selected.is_none() {
            tracing::warn!(message_id = "PrOiO8XS", "no exits left to select, clearing adaptive filters");
            *self = Self::default();
            // Select again at once (Ombra patch P29). Upstream returned no exit here, so with one matching exit (a pinned
            // server) each second connect failed with `NoExit` after its relay race.
            selected = self.best_exit(selector, exits, relay);
        }

        if let Some(selected) = selected {
            self.selected_exit_ids.insert(selected.id.clone());
            *self.selected_datacenters.entry(selected.datacenter_id).or_insert(Saturating(0)) += 1;
            *self.selected_cities.entry(selected.city_code.clone()).or_insert(Saturating(0)) += 1;
            *self
                .selected_countries
                .entry(selected.city_code.country_code.clone())
                .or_insert(Saturating(0)) += 1;
        }

        selected
    }

    fn best_exit<'a>(&self, selector: &ExitSelector, exits: &'a [OneExit], relay: &OneRelay) -> Option<&'a OneExit> {
        exits
            .iter()
            .filter(|candidate| selector.matches(candidate))
            .filter(|candidate| !self.exclude(candidate))
            .max_by_key(|candidate| Self::rank(candidate, &relay.city_code, &relay.preferred_exits))
    }

    fn rank(candidate: &OneExit, relay_city_code: &CityCode, relay_preferred_exits: &[RelayPreferredExit]) -> (bool, bool, bool, u8, u32) {
        let is_preferred = relay_preferred_exits.iter().any(|e| e.id == candidate.id);
        let same_country = relay_city_code.country_code == candidate.city_code.country_code;
        let same_city = relay_city_code == &candidate.city_code;
        (is_preferred, same_city, same_country, candidate.tier, rand::random())
    }

    fn exclude(&self, candidate: &OneExit) -> bool {
        self.selected_exit_ids.contains(&candidate.id)
            || self.selected_datacenters.get(&candidate.datacenter_id) >= Some(&Saturating(2))
            || self.selected_cities.get(&candidate.city_code) >= Some(&Saturating(4))
            || self.selected_countries.get(&candidate.city_code.country_code) >= Some(&Saturating(6))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn exit(id: &str, datacenter_id: u32) -> OneExit {
        OneExit {
            id: id.into(),
            city_code: CityCode { country_code: CountryCode("se".into()), city_code: "sto".into() },
            city_name: "Stockholm".into(),
            provider_id: id.into(),
            provider_url: String::new(),
            provider_name: String::new(),
            provider_homepage_url: String::new(),
            datacenter_id,
            tier: 0,
        }
    }

    fn relay() -> OneRelay {
        OneRelay {
            id: "relay-1".into(),
            city_code: CityCode { country_code: CountryCode("de".into()), city_code: "fra".into() },
            city_name: "Frankfurt".into(),
            preferred_exits: Vec::new(),
            ip_v4: "192.0.2.10".parse().unwrap(),
            ports: vec![443],
            tls_cert: Vec::new(),
        }
    }

    #[test]
    fn a_pinned_exit_is_selected_at_each_attempt() {
        let exits = [exit("se-sto-001", 1), exit("se-sto-002", 2)];
        let pinned = ExitSelector::Exit { id: "se-sto-001".into() };
        let mut state = ExitSelectionState::default();
        for _ in 0..3 {
            assert_eq!(
                state.select_next_exit(&pinned, &exits, &relay()).map(|e| e.id.as_str()),
                Some("se-sto-001")
            );
        }
    }

    #[test]
    fn other_exits_come_first_then_the_filters_clear() {
        let exits = [exit("se-sto-001", 1), exit("se-sto-002", 2)];
        let mut state = ExitSelectionState::default();
        let first = state.select_next_exit(&ExitSelector::Any {}, &exits, &relay()).unwrap().id.clone();
        let second = state.select_next_exit(&ExitSelector::Any {}, &exits, &relay()).unwrap().id.clone();
        assert_ne!(first, second);
        assert!(state.select_next_exit(&ExitSelector::Any {}, &exits, &relay()).is_some());
        let none = ExitSelector::Country { country_code: CountryCode("ch".into()) };
        assert!(state.select_next_exit(&none, &exits, &relay()).is_none());
    }
}
