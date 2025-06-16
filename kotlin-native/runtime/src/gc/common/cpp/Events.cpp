#include "Events.hpp"

namespace {
    kotlin::gc::EventsCounter eventsCounter_;
}

void kotlin::gc::gcEvent(GCState gcEvent) {
    eventsCounter_.gcEvent(gcEvent);
}

void kotlin::gc::mutatorEvent(MutatorEvent mutatorEvent) {
    eventsCounter_.mutatorEvent(mutatorEvent);
}
