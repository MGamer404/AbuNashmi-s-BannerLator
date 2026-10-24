use crate::proto_wire::{Reader, WireType, Writer};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CPublishedFileGetUserFilesRequest {
    pub steamid: u64,
    pub appid: u32,
    pub page: u32,
    pub numperpage: u32,
    pub request_type: String,
    pub filetype: u32,
}

impl Default for CPublishedFileGetUserFilesRequest {
    fn default() -> Self {
        Self {
            steamid: 0,
            appid: 0,
            page: 1,
            numperpage: 1,
            request_type: String::new(),
            filetype: 0,
        }
    }
}

impl CPublishedFileGetUserFilesRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.fixed64_field(1, self.steamid);
        w.uint32_field(2, self.appid);
        w.uint32_field(4, self.page);
        w.uint32_field(5, self.numperpage);
        w.string_field(6, &self.request_type);
        w.uint32_field(14, self.filetype);
        out
    }
}

/// `CPublishedFile_QueryFiles_Request` (the Workshop browse call): which page of which ranking, for
/// which app, optionally narrowed by tags, free text or a trend window in days.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct CPublishedFileQueryFilesRequest {
    /// `EPublishedFileQueryType`: 0 = most popular, 1 = newest, 3 = trending, 9 = most subscribed,
    /// 12 = text search, 21 = recently updated.
    pub query_type: u32,
    /// 1-based, like `GetUserFiles`.
    pub page: u32,
    pub numperpage: u32,
    /// The app that consumes the items (the game being browsed).
    pub appid: u32,
    pub required_tags: Vec<String>,
    pub excluded_tags: Vec<String>,
    /// Proto default is true (an item must carry every required tag); sent explicitly either way.
    pub match_all_tags: bool,
    pub search_text: String,
    /// `EPublishedFileInfoMatchingFileType`; 0 = normal items.
    pub filetype: u32,
    /// Trend window for `query_type` 3; 0 leaves it to the server.
    pub days: u32,
}

impl Default for CPublishedFileQueryFilesRequest {
    fn default() -> Self {
        Self {
            query_type: 0,
            page: 1,
            numperpage: 30,
            appid: 0,
            required_tags: Vec::new(),
            excluded_tags: Vec::new(),
            match_all_tags: true,
            search_text: String::new(),
            filetype: 0,
            days: 0,
        }
    }
}

impl CPublishedFileQueryFilesRequest {
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint32_field(1, self.query_type);
        w.uint32_field(2, self.page);
        w.uint32_field(3, self.numperpage);
        w.uint32_field(5, self.appid);
        for tag in &self.required_tags {
            w.string_field(6, tag);
        }
        for tag in &self.excluded_tags {
            w.string_field(7, tag);
        }
        w.bool_field_force(8, self.match_all_tags);
        w.string_field(11, &self.search_text);
        w.uint32_field(12, self.filetype);
        w.uint32_field(14, self.days);
        // What the browse list shows: votes, tags, previews, children, the short description and the
        // detail block (sizes, urls), with BBCode stripped from the text.
        w.bool_field(17, true);
        w.bool_field(18, true);
        w.bool_field(20, true);
        w.bool_field(21, true);
        w.bool_field(22, true);
        w.bool_field(37, true);
        w.bool_field(38, true);
        out
    }
}

/// `CPublishedFile_Subscribe_Request` / `CPublishedFile_Unsubscribe_Request`: they share fields 1-4;
/// only Subscribe has `include_dependencies` (5).
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct CPublishedFileSubscriptionRequest {
    pub publishedfileid: u64,
    /// The `list_type` Steam expects for the subscribed list. Not published in Valve's protobuf
    /// files, so the caller supplies it (0 = leave unset).
    pub list_type: u32,
    pub appid: i32,
    /// Ask Steam to tell any other client logged in as this account (a desktop Steam, SteamLite).
    pub notify_client: bool,
    pub include_dependencies: bool,
}

impl CPublishedFileSubscriptionRequest {
    pub fn serialize(&self, subscribe: bool) -> Vec<u8> {
        let mut out = Vec::new();
        let mut w = Writer::new(&mut out);
        w.uint64_field(1, self.publishedfileid);
        w.uint32_field(2, self.list_type);
        w.int32_field(3, self.appid);
        w.bool_field(4, self.notify_client);
        if subscribe {
            w.bool_field(5, self.include_dependencies);
        }
        out
    }
}

#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct PublishedFileTag {
    pub tag: String,
    pub display_name: String,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct PublishedFileDetails {
    pub result: u32,
    pub publishedfileid: u64,
    pub creator: u64,
    pub consumer_appid: u32,
    pub filename: String,
    pub file_size: u64,
    pub file_url: String,
    pub preview_url: String,
    pub hcontent_file: u64,
    pub title: String,
    pub file_description: String,
    pub short_description: String,
    pub time_created: u32,
    pub time_updated: u32,
    pub banned: bool,
    pub file_type: u32,
    pub subscriptions: u32,
    pub lifetime_subscriptions: u32,
    pub views: u32,
    pub num_children: u32,
    /// Image URLs of the item's additional previews.
    pub previews: Vec<String>,
    pub tags: Vec<PublishedFileTag>,
    /// Ids of child items (a collection's members, or the items this one depends on).
    pub children: Vec<u64>,
    /// 0.0 - 1.0 vote score; 0.0 when the item has no votes.
    pub vote_score: f32,
    pub votes_up: u32,
    pub votes_down: u32,
}

fn parse_tag(body: &[u8]) -> Option<PublishedFileTag> {
    let mut reader = Reader::new(body);
    let mut msg = PublishedFileTag::default();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(msg);
        };
        let handled = match (tag.field_number, tag.wire_type) {
            (1, WireType::LengthDelimited) => {
                msg.tag = reader.string()?;
                true
            }
            (3, WireType::LengthDelimited) => {
                msg.display_name = reader.string()?;
                true
            }
            _ => false,
        };
        if !handled && !reader.skip(tag.wire_type) {
            return None;
        }
    }
    Some(msg)
}

/// The `url` of a `PublishedFileDetails.Preview`; None for entries without one (YouTube previews).
fn parse_preview_url(body: &[u8]) -> Option<String> {
    let mut reader = Reader::new(body);
    let mut url = String::new();
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(url).filter(|u| !u.is_empty());
        };
        if tag.field_number == 3 && tag.wire_type == WireType::LengthDelimited {
            url = reader.string()?;
        } else if !reader.skip(tag.wire_type) {
            return None;
        }
    }
    Some(url).filter(|u| !u.is_empty())
}

/// The `publishedfileid` of a `PublishedFileDetails.Child`.
fn parse_child_id(body: &[u8]) -> Option<u64> {
    let mut reader = Reader::new(body);
    let mut id = 0u64;
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some(id).filter(|v| *v != 0);
        };
        if tag.field_number == 1 && tag.wire_type == WireType::Varint {
            id = reader.u64()?;
        } else if !reader.skip(tag.wire_type) {
            return None;
        }
    }
    Some(id).filter(|v| *v != 0)
}

/// `PublishedFileDetails.VoteData`: (score, votes_up, votes_down).
fn parse_vote_data(body: &[u8]) -> Option<(f32, u32, u32)> {
    let mut reader = Reader::new(body);
    let (mut score, mut up, mut down) = (0f32, 0u32, 0u32);
    while !reader.eof() {
        let Some(tag) = reader.next_tag() else {
            return reader.ok().then_some((score, up, down));
        };
        let handled = match (tag.field_number, tag.wire_type) {
            (1, WireType::Fixed32) => {
                score = f32::from_bits(reader.fixed32()?);
                true
            }
            (2, WireType::Varint) => {
                up = reader.u32()?;
                true
            }
            (3, WireType::Varint) => {
                down = reader.u32()?;
                true
            }
            _ => false,
        };
        if !handled && !reader.skip(tag.wire_type) {
            return None;
        }
    }
    Some((score, up, down))
}

impl PublishedFileDetails {
    pub fn parse(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            let handled = match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => {
                    msg.result = reader.u32()?;
                    true
                }
                (2, WireType::Varint) => {
                    msg.publishedfileid = reader.u64()?;
                    true
                }
                (3, WireType::Fixed64) => {
                    msg.creator = reader.fixed64()?;
                    true
                }
                (5, WireType::Varint) => {
                    msg.consumer_appid = reader.u32()?;
                    true
                }
                (7, WireType::LengthDelimited) => {
                    msg.filename = reader.string()?;
                    true
                }
                (8, WireType::Varint) => {
                    msg.file_size = reader.u64()?;
                    true
                }
                (10, WireType::LengthDelimited) => {
                    msg.file_url = reader.string()?;
                    true
                }
                (11, WireType::LengthDelimited) => {
                    msg.preview_url = reader.string()?;
                    true
                }
                (14, WireType::Fixed64) => {
                    msg.hcontent_file = reader.fixed64()?;
                    true
                }
                (16, WireType::LengthDelimited) => {
                    msg.title = reader.string()?;
                    true
                }
                (17, WireType::LengthDelimited) => {
                    msg.file_description = reader.string()?;
                    true
                }
                (18, WireType::LengthDelimited) => {
                    msg.short_description = reader.string()?;
                    true
                }
                (19, WireType::Varint) => {
                    msg.time_created = reader.u32()?;
                    true
                }
                (20, WireType::Varint) => {
                    msg.time_updated = reader.u32()?;
                    true
                }
                (28, WireType::Varint) => {
                    msg.banned = reader.boolean()?;
                    true
                }
                (34, WireType::Varint) => {
                    msg.file_type = reader.u32()?;
                    true
                }
                (36, WireType::Varint) => {
                    msg.subscriptions = reader.u32()?;
                    true
                }
                (39, WireType::Varint) => {
                    msg.lifetime_subscriptions = reader.u32()?;
                    true
                }
                (42, WireType::Varint) => {
                    msg.views = reader.u32()?;
                    true
                }
                (49, WireType::Varint) => {
                    msg.num_children = reader.u32()?;
                    true
                }
                (51, WireType::LengthDelimited) => {
                    if let Some(url) = parse_preview_url(reader.bytes()?) {
                        msg.previews.push(url);
                    }
                    true
                }
                (52, WireType::LengthDelimited) => {
                    if let Some(tag) = parse_tag(reader.bytes()?) {
                        msg.tags.push(tag);
                    }
                    true
                }
                (53, WireType::LengthDelimited) => {
                    if let Some(id) = parse_child_id(reader.bytes()?) {
                        msg.children.push(id);
                    }
                    true
                }
                (55, WireType::LengthDelimited) => {
                    if let Some((score, up, down)) = parse_vote_data(reader.bytes()?) {
                        msg.vote_score = score;
                        msg.votes_up = up;
                        msg.votes_down = down;
                    }
                    true
                }
                _ => false,
            };
            if !handled && !reader.skip(tag.wire_type) {
                return None;
            }
        }
        Some(msg)
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct CPublishedFileGetUserFilesResponse {
    pub total: u32,
    pub startindex: u32,
    pub publishedfiledetails: Vec<PublishedFileDetails>,
}

impl CPublishedFileGetUserFilesResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            let handled = match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => {
                    msg.total = reader.u32()?;
                    true
                }
                (2, WireType::Varint) => {
                    msg.startindex = reader.u32()?;
                    true
                }
                (3, WireType::LengthDelimited) => {
                    msg.publishedfiledetails
                        .push(PublishedFileDetails::parse(reader.bytes()?)?);
                    true
                }
                _ => false,
            };
            if !handled && !reader.skip(tag.wire_type) {
                return None;
            }
        }
        Some(msg)
    }
}

/// `CPublishedFile_QueryFiles_Response`. Unlike `GetUserFiles`, the details are field 2.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct CPublishedFileQueryFilesResponse {
    pub total: u32,
    pub publishedfiledetails: Vec<PublishedFileDetails>,
    pub next_cursor: String,
}

impl CPublishedFileQueryFilesResponse {
    pub fn deserialize(body: &[u8]) -> Option<Self> {
        let mut reader = Reader::new(body);
        let mut msg = Self::default();
        while !reader.eof() {
            let Some(tag) = reader.next_tag() else {
                return reader.ok().then_some(msg);
            };
            let handled = match (tag.field_number, tag.wire_type) {
                (1, WireType::Varint) => {
                    msg.total = reader.u32()?;
                    true
                }
                (2, WireType::LengthDelimited) => {
                    msg.publishedfiledetails
                        .push(PublishedFileDetails::parse(reader.bytes()?)?);
                    true
                }
                (3, WireType::LengthDelimited) => {
                    msg.next_cursor = reader.string()?;
                    true
                }
                _ => false,
            };
            if !handled && !reader.skip(tag.wire_type) {
                return None;
            }
        }
        Some(msg)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_user_files_response_and_skips_schema_drift() {
        let mut detail = Vec::new();
        {
            let mut w = Writer::new(&mut detail);
            w.uint32_field(1, 1);
            w.uint64_field(2, 99);
            w.uint32_field(5, 480);
            w.string_field(7, "file.bin");
            w.uint64_field(8, 1024);
            w.string_field(10, "https://example/file");
            w.string_field(11, "https://example/preview");
            w.fixed64_field(14, 777);
            w.string_field(16, "Workshop Item");
            w.uint32_field(20, 123456);
            w.fixed64_field(5, 0xfeed);
        }

        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint32_field(1, 1);
            w.uint32_field(2, 0);
            w.submessage_field(3, &detail);
        }

        let parsed = CPublishedFileGetUserFilesResponse::deserialize(&body).unwrap();
        let detail = &parsed.publishedfiledetails[0];
        assert_eq!(parsed.total, 1);
        assert_eq!(detail.publishedfileid, 99);
        assert_eq!(detail.consumer_appid, 480);
        assert_eq!(detail.hcontent_file, 777);
        assert_eq!(detail.title, "Workshop Item");
    }

    #[test]
    fn query_files_request_uses_valve_field_numbers() {
        let request = CPublishedFileQueryFilesRequest {
            query_type: 12,
            page: 2,
            numperpage: 30,
            appid: 480,
            required_tags: vec!["Maps".to_string()],
            excluded_tags: vec!["NSFW".to_string()],
            match_all_tags: false,
            search_text: "castle".to_string(),
            filetype: 0,
            days: 7,
        };
        let body = request.serialize();
        let has = |needle: &[u8]| body.windows(needle.len()).any(|w| w == needle);
        assert!(has(&[0x08, 12])); // query_type = 1
        assert!(has(&[0x10, 2])); // page = 2
        assert!(has(&[0x18, 30])); // numperpage = 3
        assert!(has(&[0x28, 0xe0, 0x03])); // appid = 5 (480)
        assert!(has(&[0x32, 4, b'M', b'a', b'p', b's'])); // requiredtags = 6
        assert!(has(&[0x3a, 4, b'N', b'S', b'F', b'W'])); // excludedtags = 7
        assert!(has(&[0x40, 0])); // match_all_tags = 8, sent explicitly as false
        assert!(has(&[0x5a, 6, b'c', b'a', b's', b't', b'l', b'e'])); // search_text = 11
        assert!(has(&[0x70, 7])); // days = 14
        assert!(has(&[0x88, 0x01, 1])); // return_vote_data = 17
        assert!(has(&[0xa8, 0x02, 1])); // return_details = 37
        assert!(has(&[0xb0, 0x02, 1])); // strip_description_bbcode = 38
        // filetype 0 is the default, so it is not on the wire
        assert!(!has(&[0x60]));
    }

    #[test]
    fn subscription_request_only_subscribe_carries_dependencies() {
        let request = CPublishedFileSubscriptionRequest {
            publishedfileid: 1234,
            list_type: 0,
            appid: 480,
            notify_client: true,
            include_dependencies: true,
        };
        assert_eq!(
            request.serialize(true),
            vec![0x08, 0xd2, 0x09, 0x18, 0xe0, 0x03, 0x20, 1, 0x28, 1]
        );
        assert_eq!(
            request.serialize(false),
            vec![0x08, 0xd2, 0x09, 0x18, 0xe0, 0x03, 0x20, 1]
        );

        let with_list = CPublishedFileSubscriptionRequest {
            list_type: 1,
            ..request
        };
        assert!(with_list.serialize(true).windows(2).any(|w| w == [0x10, 1]));
    }

    #[test]
    fn parses_query_files_response_with_nested_details() {
        let mut tag = Vec::new();
        {
            let mut w = Writer::new(&mut tag);
            w.string_field(1, "Maps");
            w.string_field(3, "Maps (display)");
        }
        let mut preview = Vec::new();
        {
            let mut w = Writer::new(&mut preview);
            w.uint64_field(1, 5);
            w.string_field(3, "https://example/shot1.jpg");
        }
        let mut youtube_preview = Vec::new();
        {
            let mut w = Writer::new(&mut youtube_preview);
            w.string_field(6, "abcdefghijk");
        }
        let mut child = Vec::new();
        {
            let mut w = Writer::new(&mut child);
            w.uint64_field(1, 555);
        }
        let mut votes = Vec::new();
        {
            let mut w = Writer::new(&mut votes);
            w.tag(1, WireType::Fixed32);
            w.raw_bytes(&0.75f32.to_le_bytes());
            w.uint32_field(2, 90);
            w.uint32_field(3, 10);
        }
        let mut detail = Vec::new();
        {
            let mut w = Writer::new(&mut detail);
            w.uint64_field(2, 4242);
            w.fixed64_field(3, 76561198000000000);
            w.uint32_field(5, 480);
            w.uint64_field(8, 2048);
            w.fixed64_field(14, 999);
            w.string_field(16, "Castle Map");
            w.string_field(17, "Long text");
            w.string_field(18, "Short text");
            w.uint32_field(19, 100);
            w.uint32_field(20, 200);
            w.uint32_field(36, 12);
            w.uint32_field(39, 34);
            w.uint32_field(42, 56);
            w.submessage_field(51, &preview);
            w.submessage_field(51, &youtube_preview);
            w.submessage_field(52, &tag);
            w.submessage_field(53, &child);
            w.submessage_field(55, &votes);
        }
        let mut body = Vec::new();
        {
            let mut w = Writer::new(&mut body);
            w.uint32_field(1, 77);
            w.submessage_field(2, &detail);
            w.string_field(3, "cursor-token");
        }

        let parsed = CPublishedFileQueryFilesResponse::deserialize(&body).unwrap();
        assert_eq!(parsed.total, 77);
        assert_eq!(parsed.next_cursor, "cursor-token");
        let item = &parsed.publishedfiledetails[0];
        assert_eq!(item.publishedfileid, 4242);
        assert_eq!(item.creator, 76561198000000000);
        assert_eq!(item.hcontent_file, 999);
        assert_eq!(item.title, "Castle Map");
        assert_eq!(item.file_description, "Long text");
        assert_eq!(item.short_description, "Short text");
        assert_eq!((item.time_created, item.time_updated), (100, 200));
        assert_eq!(
            (item.subscriptions, item.lifetime_subscriptions, item.views),
            (12, 34, 56)
        );
        assert_eq!(item.previews, vec!["https://example/shot1.jpg".to_string()]);
        assert_eq!(item.tags[0].tag, "Maps");
        assert_eq!(item.tags[0].display_name, "Maps (display)");
        assert_eq!(item.children, vec![555]);
        assert_eq!((item.votes_up, item.votes_down), (90, 10));
        assert!((item.vote_score - 0.75).abs() < f32::EPSILON);
    }
}
