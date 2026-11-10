"""Exercise the production Room history SQL against SQLite without a device."""
from pathlib import Path
import re
import sqlite3
import unittest


SOURCE = Path(__file__).resolve().parents[1] / "data/storage/src/main/kotlin/dev/vivian/mobilebc/data/storage/db/Daos.kt"
SQL = re.search(r'@Query\(\s*"""([^\"]+)""",\s*\)\s*fun observeHistory\(', SOURCE.read_text(encoding="utf-8")).group(1)


class HistoryQueryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.execute("""CREATE TABLE messages (
            ownerMemberNumber INTEGER, conversationId TEXT, messageId TEXT,
            rawRoomName TEXT, kind TEXT, transport TEXT, senderMemberNumber INTEGER,
            targetMemberNumber INTEGER, senderIsSelf INTEGER, visibility TEXT,
            text TEXT, timestampMillis INTEGER)""")

    def tearDown(self):
        self.db.close()

    def insert(self, message_id, **overrides):
        row = dict(ownerMemberNumber=1, conversationId="room:test", messageId=message_id,
                   rawRoomName="Room", kind="Chat", transport="RoomChat",
                   senderMemberNumber=2, targetMemberNumber=None, senderIsSelf=0,
                   visibility="Visible", text="hello 历史 100%_", timestampMillis=100)
        row.update(overrides)
        self.db.execute(f"INSERT INTO messages ({','.join(row)}) VALUES ({','.join('?' for _ in row)})", list(row.values()))

    def search(self, **overrides):
        args = dict(ownerMemberNumber=1, conversationId="room:test", roomName="Room",
                    query="", fromMillis=None, untilMillis=None, oldestFirst=False, limit=101)
        args.update(overrides)
        return [row["messageId"] for row in self.db.execute(SQL, args)]

    def test_account_visibility_and_private_room_scope(self):
        self.insert("public")
        self.insert("other-account", ownerMemberNumber=3)
        self.insert("hidden", visibility="HiddenProtocol")
        self.insert("incoming", conversationId="message:2", kind="Whisper", transport="RoomWhisper", targetMemberNumber=1)
        self.insert("outgoing", conversationId="message:2", kind="Whisper", transport="RoomWhisper", senderMemberNumber=1, senderIsSelf=1, targetMemberNumber=2)
        self.insert("private-others", conversationId="message:3", kind="Whisper", transport="RoomWhisper", targetMemberNumber=3)
        self.insert("other-room", conversationId="message:2", kind="Whisper", transport="RoomWhisper", targetMemberNumber=1, rawRoomName="Elsewhere")
        self.assertEqual({"public", "incoming", "outgoing"}, set(self.search()))
        self.assertEqual({"incoming", "outgoing", "other-room"}, set(self.search(conversationId="message:2", roomName=None)))

    def test_literal_keywords_and_date_end_exclusion(self):
        self.insert("start", timestampMillis=100)
        self.insert("end", timestampMillis=200)
        self.insert("before", timestampMillis=99)
        self.insert("different", text="another message", timestampMillis=150)
        for keyword in ("HELLO", "历史", "%_"):
            self.assertEqual(["start"], self.search(query=keyword, fromMillis=100, untilMillis=200))

    def test_old_match_beyond_500_and_bounded_context_order(self):
        for index in range(1201):
            self.insert(str(index), timestampMillis=index, text="needle" if index == 0 else "message")
        self.assertEqual(["0"], self.search(query="needle"))
        self.assertEqual(["100", "99", "98"], self.search(untilMillis=101, limit=3))
        self.assertEqual(["100", "101", "102"], self.search(fromMillis=100, oldestFirst=True, limit=3))


if __name__ == "__main__":
    unittest.main()
