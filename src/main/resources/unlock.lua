
if (redis.call('get', KEYS[1]) == VRGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0
