# SAP JCo Monitor

Java-based SAP system monitoring utility using SAP Java Connector (JCo) 3.1.

## Purpose

Automated RFC-based monitoring for S/4HANA, BW, and ECC systems covering:

- Lock entries (SM12)
- Update errors (SM13)
- Queue status (SMQ1)
- Application server status (SM51)
- Background jobs (SM37)
- Short dumps (ST22)
- Logon group response times (SMLG)

Exit codes: 0=OK, 1=Warning, 2=Critical (ideal for cron + alerting)

## Requirements

- Java 11+
- SAP JCo 3.1 (sapjco3.jar + native library for your platform)
- Valid SAP user with RFC authorization

## Project Structure

```
sap-jco-monitor/
├── src/main/java/com/sap/monitor/SAPSystemMonitor.java
├── lib/sapjco3.jar          # Add your JCo jar here
├── destinations/
│   ├── S4D.jcoDestination.template   # Committed; copy to S4D.jcoDestination
│   └── S4D.jcoDestination            # Your real creds (git-ignored)
├── README.md
└── .gitignore
```

## Quick Start

1. Place `sapjco3.jar` and the native library in `lib/`

2. Configure a destination:

   ```bash
   cp destinations/S4D.jcoDestination.template destinations/S4D.jcoDestination
   nano destinations/S4D.jcoDestination
   ```

3. Compile and run:

   ```bash
   javac -cp "lib/*:src/main/java" src/main/java/com/sap/monitor/SAPSystemMonitor.java
   java -cp "lib/*:src/main/java" com.sap.monitor.SAPSystemMonitor S4D
   ```

## Cron Example

```bash
*/5 * * * * cd /opt/sap-jco-monitor && java -cp "lib/*:src/main/java" com.sap.monitor.SAPSystemMonitor S4D >> /var/log/sap-monitor.log 2>&1
```

## License

Internal tool.