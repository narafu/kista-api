CREATE TABLE admin_runtime_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO admin_runtime_settings (setting_key, setting_value)
VALUES ('runtime', '{
  "approvalRequired": true,
  "brokers": {
    "KIS": {"enabled": true},
    "TOSS": {"enabled": true}
  },
  "strategies": {
    "INFINITE": {
      "enabled": true,
      "ticker": {"customizable": true, "allowedValues": ["MAGX", "USD", "TQQQ", "SOXL"], "defaultValue": "SOXL"},
      "divisionCount": {"customizable": true, "allowedValues": [20, 30, 40], "defaultValue": 20},
      "recurringMode": null,
      "bandWidth": null,
      "intervalWeeks": null
    },
    "PRIVACY": {
      "enabled": true,
      "ticker": {"customizable": false, "allowedValues": ["SOXL"], "defaultValue": "SOXL"},
      "divisionCount": null,
      "recurringMode": null,
      "bandWidth": null,
      "intervalWeeks": null
    },
    "VR": {
      "enabled": true,
      "ticker": {"customizable": false, "allowedValues": ["TQQQ"], "defaultValue": "TQQQ"},
      "divisionCount": null,
      "recurringMode": {"customizable": true, "allowedValues": ["DEPOSIT", "HOLD", "WITHDRAW"], "defaultValue": "HOLD"},
      "bandWidth": {"customizable": true, "allowedValues": [10, 15, 20], "defaultValue": 15},
      "intervalWeeks": {"customizable": true, "allowedValues": [1, 2, 4], "defaultValue": 2}
    }
  }
}'::jsonb);
