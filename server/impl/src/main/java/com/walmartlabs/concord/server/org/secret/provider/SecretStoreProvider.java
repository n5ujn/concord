package com.walmartlabs.concord.server.org.secret.provider;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.walmartlabs.concord.server.org.secret.SecretStoreType;
import com.walmartlabs.concord.server.org.secret.store.SecretStore;
import com.walmartlabs.ollie.config.Config;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;

@Named
public class SecretStoreProvider {

    private final Collection<SecretStore> stores;
    private final int maxSecretDataSize;
    private final SecretStoreType defaultSecretStoreType;

    @Inject
    public SecretStoreProvider(Collection<SecretStore> stores,
                               @Config("secretStore.maxSecretDataSize") int maxSecretDataSize,
                               @Config("secretStore.default") String defaultStore) {
        this.stores = stores;
        this.maxSecretDataSize = maxSecretDataSize;
        this.defaultSecretStoreType = SecretStoreType.valueOf(defaultStore.toUpperCase());
    }

    public SecretStore getSecretStore(SecretStoreType secretSourceType) {
        for (SecretStore secretStore : stores) {
            if (secretSourceType == secretStore.getType()) {
                if (secretStore.isEnabled()) {
                    return secretStore;
                }

                throw new IllegalArgumentException("Secret store of type " + secretStore.getType() + " is not enabled!");
            }
        }

        throw new IllegalArgumentException("Secret store of type " + secretSourceType + " is not found!");
    }

    public Collection<SecretStore> getActiveSecretStores() {
        Collection<SecretStore> activeStores = new ArrayList<>();

        for (SecretStore secretStore : stores) {
            if (secretStore.isEnabled()) {
                activeStores.add(secretStore);
            }
        }

        return activeStores;
    }

    public SecretStoreType getDefaultStoreType() {
        return defaultSecretStoreType;
    }

    public int getMaxSecretDataSize() {
        return maxSecretDataSize;
    }
}
