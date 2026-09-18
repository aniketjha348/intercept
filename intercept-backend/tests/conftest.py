"""Tests run against the in-memory store — never pollute real Postgres."""
import os

os.environ["DATABASE_URL"] = ""
