# NetworksGroup12
Java-based peer-to-peer file sharing system inspired by BitTorrent, implementing TCP communication, file piece exchange, and choking/unchoking mechanisms.

# Team
Ishaan Singh - peer state
Nathan Field - configuration, piece storage, and logging
Nicholas Borden - protocol messages and stream reading

# Midpoint Progress
As of October 14, we have over 500 lines of Java source code. All current files in `src` compile together with Java 21.

Our code covers handshake and message formatting, reading complete messages from a stream, configuration reading, piece storage, logging, and peer state. These parts are still separate. We do not have a complete running peer or working file transfer yet.

# What's next
By late October, we plan to connect two peers and transfer pieces. In early November, we plan to integrate multiple peers and scheduling. We will use the rest of November to test complete file transfers, shutdown, and the demo.
